/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.util.pool.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.util.TestLogger;
import reactor.util.Loggers;
import reactor.util.pool.api.PooledRef;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * @author Simon Baslé
 */
class ThreadAffinityPoolTest {

    public static final class PoolableTest implements Disposable {

        private static AtomicInteger defaultId = new AtomicInteger();

        private int usedUp;
        private int discarded;
        private final int id;

        PoolableTest() {
            this(defaultId.incrementAndGet());
        }

        PoolableTest(int id) {
            this.id = id;
            this.usedUp = 0;
        }

        void clean() {
            this.usedUp++;
        }

        boolean isHealthy() {
            return usedUp < 2;
        }

        @Override
        public void dispose() {
            discarded++;
        }

        @Override
        public boolean isDisposed() {
            return discarded > 0;
        }

        @Override
        public String toString() {
            return "PoolableTest{id=" + id + ", used=" + usedUp + "}";
        }
    }

    private static final class PoolableTestConfig extends DefaultPoolConfig<PoolableTest> {

        private PoolableTestConfig(int minSize, int maxSize, Mono<PoolableTest> allocator) {
            super(minSize, maxSize,
                    allocator,
                    pt -> Mono.fromRunnable(pt::clean),
                    slot -> !slot.poolable().isHealthy(),
                    null);
        }

        private PoolableTestConfig(int minSize, int maxSize, Mono<PoolableTest> allocator, Scheduler deliveryScheduler) {
            super(minSize, maxSize,
                    allocator,
                    pt -> Mono.fromRunnable(pt::clean),
                    slot -> !slot.poolable().isHealthy(),
                    deliveryScheduler);
        }

        private PoolableTestConfig(int minSize, int maxSize, Mono<PoolableTest> allocator,
                                   Consumer<? super PoolableTest> additionalCleaner) {
            super(minSize, maxSize,
                    allocator,
                    poolableTest -> Mono.fromRunnable(() -> {
                        poolableTest.clean();
                        additionalCleaner.accept(poolableTest);
                    }),
                    slot -> !slot.poolable().isHealthy(),
                    null);
        }
    }

    @Test
    void demonstrateBorrowInScopePipeline() throws InterruptedException {
        AtomicInteger counter = new AtomicInteger();
        AtomicReference<String> releaseRef = new AtomicReference<>();

        ThreadAffinityPool<String> pool = new ThreadAffinityPool<>(new DefaultPoolConfig<>(0, 1, Mono.just("Hello Reactive World"),
                s -> Mono.fromRunnable(()-> releaseRef.set(s)), null, null));

        Flux<String> words = pool.borrowInScope(m -> m
                //simulate deriving a value from the resource (ie. query from DB connection)
                .map(resource -> resource.split(" "))
                //then further process the derived value to produce multiple values (ie. rows from a query)
                .flatMapIterable(Arrays::asList)
                //and all that with latency
                .delayElements(Duration.ofMillis(500)));

        words.subscribe(v -> counter.incrementAndGet());
        assertThat(counter).hasValue(0);

        Thread.sleep(1000);
        //we're in the middle of processing the "rows"
        assertThat(counter).as("before all emitted").hasValue(2);
        assertThat(releaseRef).as("still borrowing").hasValue(null);

        Thread.sleep(500);
        //we've finished processing, let's check resource has been automatically released
        assertThat(counter).as("after all emitted").hasValue(3);
        assertThat(pool.live).as("live").isOne();
        assertThat(pool.available_mpmc).as("available").hasSize(1);
        assertThat(releaseRef).as("released").hasValue("Hello Reactive World");
    }

    @Nested
    @DisplayName("Tests around the borrow() manual mode of borrowing")
    class BorrowTest {

        @Test
        void smokeTest() throws InterruptedException {
            AtomicInteger newCount = new AtomicInteger();
            ThreadAffinityPool<PoolableTest> pool = new ThreadAffinityPool<>(new PoolableTestConfig(2, 3,
                    Mono.defer(() -> Mono.just(new PoolableTest(newCount.incrementAndGet())))));

            List<PooledRef<PoolableTest>> borrowed1 = new ArrayList<>();
            pool.borrow().subscribe(borrowed1::add);
            pool.borrow().subscribe(borrowed1::add);
            pool.borrow().subscribe(borrowed1::add);
            List<PooledRef<PoolableTest>> borrowed2 = new ArrayList<>();
            pool.borrow().subscribe(borrowed2::add);
            pool.borrow().subscribe(borrowed2::add);
            pool.borrow().subscribe(borrowed2::add);
            List<PooledRef<PoolableTest>> borrowed3 = new ArrayList<>();
            pool.borrow().subscribe(borrowed3::add);
            pool.borrow().subscribe(borrowed3::add);
            pool.borrow().subscribe(borrowed3::add);

            assertThat(borrowed1).hasSize(3);
            assertThat(borrowed2).isEmpty();
            assertThat(borrowed3).isEmpty();

            Thread.sleep(1000);
            for (PooledRef<PoolableTest> slot : borrowed1) {
                slot.releaseMono().block();
            }
            assertThat(borrowed2).hasSize(3);
            assertThat(borrowed3).isEmpty();

            Thread.sleep(1000);
            for (PooledRef<PoolableTest> slot : borrowed2) {
                slot.releaseMono().block();
            }
            assertThat(borrowed3).hasSize(3);

            assertThat(borrowed1)
                    .as("borrowed1/2 all used up")
                    .hasSameElementsAs(borrowed2)
                    .allSatisfy(slot -> assertThat(slot.poolable().usedUp).isEqualTo(2));

            assertThat(borrowed3)
                    .as("borrowed3 all new")
                    .allSatisfy(slot -> assertThat(slot.poolable().usedUp).isZero());
        }

        @Test
        void smokeTestAsync() throws InterruptedException {
            AtomicInteger newCount = new AtomicInteger();
            ThreadAffinityPool<PoolableTest> pool = new ThreadAffinityPool<>(new PoolableTestConfig(2, 3,
                    Mono.defer(() -> Mono.just(new PoolableTest(newCount.incrementAndGet())))
                            .subscribeOn(Schedulers.newParallel("poolable test allocator"))));

            List<PooledRef<PoolableTest>> borrowed1 = new ArrayList<>();
            CountDownLatch latch1 = new CountDownLatch(3);
            pool.borrow().subscribe(borrowed1::add, Throwable::printStackTrace, latch1::countDown);
            pool.borrow().subscribe(borrowed1::add, Throwable::printStackTrace, latch1::countDown);
            pool.borrow().subscribe(borrowed1::add, Throwable::printStackTrace, latch1::countDown);

            List<PooledRef<PoolableTest>> borrowed2 = new ArrayList<>();
            pool.borrow().subscribe(borrowed2::add);
            pool.borrow().subscribe(borrowed2::add);
            pool.borrow().subscribe(borrowed2::add);

            List<PooledRef<PoolableTest>> borrowed3 = new ArrayList<>();
            CountDownLatch latch3 = new CountDownLatch(3);
            pool.borrow().subscribe(borrowed3::add, Throwable::printStackTrace, latch3::countDown);
            pool.borrow().subscribe(borrowed3::add, Throwable::printStackTrace, latch3::countDown);
            pool.borrow().subscribe(borrowed3::add, Throwable::printStackTrace, latch3::countDown);

            if (!latch1.await(1, TimeUnit.SECONDS)) { //wait for creation of max elements
                fail("not enough elements created initially, missing " + latch1.getCount());
            }
            assertThat(borrowed1).hasSize(3);
            assertThat(borrowed2).isEmpty();
            assertThat(borrowed3).isEmpty();

            Thread.sleep(1000);
            for (PooledRef<PoolableTest> slot : borrowed1) {
                slot.releaseMono().block();
            }
            assertThat(borrowed2).hasSize(3);
            assertThat(borrowed3).isEmpty();

            Thread.sleep(1000);
            for (PooledRef<PoolableTest> slot : borrowed2) {
                slot.releaseMono().block();
            }

            if (latch3.await(2, TimeUnit.SECONDS)) { //wait for the re-creation of max elements

                assertThat(borrowed3).hasSize(3);

                assertThat(borrowed1)
                        .as("borrowed1/2 all used up")
                        .hasSameElementsAs(borrowed2)
                        .allSatisfy(slot -> assertThat(slot.poolable().usedUp).isEqualTo(2));

                assertThat(borrowed3)
                        .as("borrowed3 all new")
                        .allSatisfy(slot -> assertThat(slot.poolable().usedUp).isZero());
            }
            else {
                fail("not enough new elements generated, missing " + latch3.getCount());
            }
        }

        @Test
        void returnedReleasedIfBorrowerCancelled() {
            AtomicInteger releasedCount = new AtomicInteger();

            PoolableTestConfig testConfig = new PoolableTestConfig(1, 1,
                    Mono.fromCallable(PoolableTest::new),
                    pt -> releasedCount.incrementAndGet());
            ThreadAffinityPool<PoolableTest> pool = new ThreadAffinityPool<>(testConfig);

            //borrow the only element
            PooledRef<PoolableTest> slot = pool.borrow().block();
            assertThat(slot).isNotNull();

            pool.borrow().subscribe().dispose();

            assertThat(releasedCount).as("before returning").hasValue(0);

            //release the element, which should forward to the cancelled second borrow, itself also cleaning
            slot.releaseMono().block();

            assertThat(releasedCount).as("after returning").hasValue(2);
        }

        @Test
        void allocatedReleasedIfBorrowerCancelled() {
            Scheduler scheduler = Schedulers.newParallel("poolable test allocator");
            AtomicInteger newCount = new AtomicInteger();
            AtomicInteger releasedCount = new AtomicInteger();

            PoolableTestConfig testConfig = new PoolableTestConfig(0, 1,
                    Mono.defer(() -> Mono.delay(Duration.ofMillis(50)).thenReturn(new PoolableTest(newCount.incrementAndGet())))
                            .subscribeOn(scheduler),
                    pt -> releasedCount.incrementAndGet());
            ThreadAffinityPool<PoolableTest> pool = new ThreadAffinityPool<>(testConfig);

            //borrow the only element and immediately dispose
            pool.borrow().subscribe().dispose();

            //release due to cancel is async, give it a bit of time
            await()
                    .atMost(100, TimeUnit.MILLISECONDS)
                    .with().pollInterval(10, TimeUnit.MILLISECONDS)
                    .untilAsserted(
                            () -> assertThat(releasedCount).as("released").hasValue(1));

            assertThat(newCount).as("created").hasValue(1);
        }

        @Test
        @Tag("loops")
        void allocatedReleasedOrAbortedIfCancelRequestRace_loop() throws InterruptedException {
            AtomicInteger newCount = new AtomicInteger();
            AtomicInteger releasedCount = new AtomicInteger();
            for (int i = 0; i < 100; i++) {
                allocatedReleasedOrAbortedIfCancelRequestRace(i, newCount, releasedCount, i % 2 == 0);
            }
            System.out.println("Total release of " + releasedCount.get() + " for " + newCount.get() + " created over 100 rounds");
        }

        @Test
        void allocatedReleasedOrAbortedIfCancelRequestRace() throws InterruptedException {
            allocatedReleasedOrAbortedIfCancelRequestRace(0, new AtomicInteger(), new AtomicInteger(), true);
            allocatedReleasedOrAbortedIfCancelRequestRace(1, new AtomicInteger(), new AtomicInteger(), false);

        }

        void allocatedReleasedOrAbortedIfCancelRequestRace(int round, AtomicInteger newCount, AtomicInteger releasedCount, boolean cancelFirst) throws InterruptedException {
            Scheduler scheduler = Schedulers.newParallel("poolable test allocator");

            PoolableTestConfig testConfig = new PoolableTestConfig(0, 1,
                    Mono.defer(() -> Mono.delay(Duration.ofMillis(50)).thenReturn(new PoolableTest(newCount.incrementAndGet())))
                            .subscribeOn(scheduler),
                    pt -> releasedCount.incrementAndGet());
            ThreadAffinityPool<PoolableTest> pool = new ThreadAffinityPool<>(testConfig);

            //borrow the only element and capture the subscription, don't request just yet
            CountDownLatch latch = new CountDownLatch(1);
            final BaseSubscriber<PooledRef<PoolableTest>> baseSubscriber = new BaseSubscriber<PooledRef<PoolableTest>>() {
                @Override
                protected void hookOnSubscribe(Subscription subscription) {
                    //don't request
                    latch.countDown();
                }
            };
            pool.borrow().subscribe(baseSubscriber);
            latch.await();

            final ExecutorService executorService = Executors.newFixedThreadPool(2);
            if (cancelFirst) {
                executorService.submit(baseSubscriber::cancel);
                executorService.submit(baseSubscriber::requestUnbounded);
            }
            else {
                executorService.submit(baseSubscriber::requestUnbounded);
                executorService.submit(baseSubscriber::cancel);
            }

            //release due to cancel is async, give it a bit of time
            await().atMost(100, TimeUnit.MILLISECONDS).with().pollInterval(10, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> assertThat(releasedCount)
                            .as("released vs created in round " + round + (cancelFirst? " (cancel first)" : " (request first)"))
                            .hasValue(newCount.get()));
        }

        @Test
        void cleanerFunctionError() {
            PoolableTestConfig testConfig = new PoolableTestConfig(0, 1, Mono.fromCallable(PoolableTest::new),
                    pt -> { throw new IllegalStateException("boom"); });
            ThreadAffinityPool<PoolableTest> pool = new ThreadAffinityPool<>(testConfig);

            PooledRef<PoolableTest> slot = pool.borrow().block();

            assertThat(slot).isNotNull();

            StepVerifier.create(slot.releaseMono())
                    .verifyErrorMessage("boom");
        }

        @Test
        void cleanerFunctionErrorDiscards() {
            PoolableTestConfig testConfig = new PoolableTestConfig(0, 1, Mono.fromCallable(PoolableTest::new),
                    pt -> { throw new IllegalStateException("boom"); });
            ThreadAffinityPool<PoolableTest> pool = new ThreadAffinityPool<>(testConfig);

            PooledRef<PoolableTest> slot = pool.borrow().block();

            assertThat(slot).isNotNull();

            StepVerifier.create(slot.releaseMono())
                    .verifyErrorMessage("boom");

            assertThat(slot.poolable().discarded).as("discarded despite cleaner error").isEqualTo(1);
        }

        @Test
        void defaultThreadDeliveringWhenHasElements() throws InterruptedException {
            AtomicReference<String> threadName = new AtomicReference<>();
            Scheduler borrowScheduler = Schedulers.newSingle("borrow");
            PoolableTestConfig testConfig = new PoolableTestConfig(1, 1,
                    Mono.fromCallable(PoolableTest::new)
                            .subscribeOn(Schedulers.newParallel("poolable test allocator")));
            ThreadAffinityPool<PoolableTest> pool = new ThreadAffinityPool<>(testConfig);

            //the pool is started with one available element
            //we prepare to borrow it
            Mono<PooledRef<PoolableTest>> borrower = pool.borrow();
            CountDownLatch latch = new CountDownLatch(1);

            //we actually request the borrow from a separate thread and see from which thread the element was delivered
            borrowScheduler.schedule(() -> borrower.subscribe(v -> threadName.set(Thread.currentThread().getName()), e -> latch.countDown(), latch::countDown));
            latch.await(1, TimeUnit.SECONDS);

            assertThat(threadName.get())
                    .startsWith("borrow-");
        }

        @Test
        void defaultThreadDeliveringWhenNoElementsButNotFull() throws InterruptedException {
            AtomicReference<String> threadName = new AtomicReference<>();
            Scheduler borrowScheduler = Schedulers.newSingle("borrow");
            PoolableTestConfig testConfig = new PoolableTestConfig(0, 1,
                    Mono.fromCallable(PoolableTest::new)
                            .subscribeOn(Schedulers.newParallel("poolable test allocator")));
            ThreadAffinityPool<PoolableTest> pool = new ThreadAffinityPool<>(testConfig);

            //the pool is started with no elements, and has capacity for 1
            //we prepare to borrow, which would allocate the element
            Mono<PooledRef<PoolableTest>> borrower = pool.borrow();
            CountDownLatch latch = new CountDownLatch(1);

            //we actually request the borrow from a separate thread, but the allocation also happens in a dedicated thread
            //we look at which thread the element was delivered from
            borrowScheduler.schedule(() -> borrower.subscribe(v -> threadName.set(Thread.currentThread().getName()), e -> latch.countDown(), latch::countDown));
            latch.await(1, TimeUnit.SECONDS);

            assertThat(threadName.get())
                    .startsWith("poolable test allocator-");
        }

        @Test
        void defaultThreadDeliveringWhenNoElementsAndFull() throws InterruptedException {
            AtomicReference<String> threadName = new AtomicReference<>();
            Scheduler borrowScheduler = Schedulers.newSingle("borrow");
            Scheduler releaseScheduler = Schedulers.fromExecutorService(
                    Executors.newSingleThreadScheduledExecutor((r -> new Thread(r,"release"))));
            PoolableTestConfig testConfig = new PoolableTestConfig(1, 1,
                    Mono.fromCallable(PoolableTest::new)
                            .subscribeOn(Schedulers.newParallel("poolable test allocator")));
            ThreadAffinityPool<PoolableTest> pool = new ThreadAffinityPool<>(testConfig);

            //the pool is started with one elements, and has capacity for 1.
            //we actually first borrow that element so that next borrow will wait for a release
            PooledRef<PoolableTest> uniqueSlot = pool.borrow().block();
            assertThat(uniqueSlot).isNotNull();

            //we prepare next borrow
            Mono<PooledRef<PoolableTest>> borrower = pool.borrow();
            CountDownLatch latch = new CountDownLatch(1);

            //we actually perform the borrow from its dedicated thread, capturing the thread on which the element will actually get delivered
            borrowScheduler.schedule(() -> borrower.subscribe(v -> threadName.set(Thread.currentThread().getName()),
                    e -> latch.countDown(), latch::countDown));
            //after a short while, we release the borrowed unique element from a third thread
            releaseScheduler.schedule(uniqueSlot.releaseMono()::block, 500, TimeUnit.MILLISECONDS);
            latch.await(1, TimeUnit.SECONDS);

            assertThat(threadName.get())
                    .isEqualTo("release");
        }

        @Test
        @Tag("loops")
        void defaultThreadDeliveringWhenNoElementsAndFullAndRaceDrain_loop() throws InterruptedException {
            AtomicInteger releaserWins = new AtomicInteger();
            AtomicInteger borrowerWins = new AtomicInteger();

            for (int i = 0; i < 100; i++) {
                defaultThreadDeliveringWhenNoElementsAndFullAndRaceDrain(i, releaserWins, borrowerWins);
            }
            //look at the stats and show them in case of assertion error. We expect all deliveries to be on either of the racer threads.
            //we expect a subset of the deliveries to happen on the second borrower's thread
            String stats = "releaser won " + releaserWins.get() + ", borrower won " + borrowerWins.get();
            assertThat(borrowerWins.get()).as(stats).isPositive();
            assertThat(releaserWins.get() + borrowerWins.get()).as(stats).isEqualTo(100);
        }

        @Test
        void defaultThreadDeliveringWhenNoElementsAndFullAndRaceDrain() throws InterruptedException {
            AtomicInteger releaserWins = new AtomicInteger();
            AtomicInteger borrowerWins = new AtomicInteger();

            defaultThreadDeliveringWhenNoElementsAndFullAndRaceDrain(0, releaserWins, borrowerWins);

            assertThat(releaserWins.get() + borrowerWins.get()).isEqualTo(1);
        }

        void defaultThreadDeliveringWhenNoElementsAndFullAndRaceDrain(int round, AtomicInteger releaserWins, AtomicInteger borrowerWins) throws InterruptedException {
            AtomicReference<String> threadName = new AtomicReference<>();
            AtomicInteger newCount = new AtomicInteger();
            Scheduler borrow1Scheduler = Schedulers.newSingle("borrow1");
            Scheduler racerReleaseScheduler = Schedulers.fromExecutorService(
                    Executors.newSingleThreadScheduledExecutor((r -> new Thread(r,"racerRelease"))));
            Scheduler racerBorrowScheduler = Schedulers.fromExecutorService(
                    Executors.newSingleThreadScheduledExecutor((r -> new Thread(r,"racerBorrow"))));

            PoolableTestConfig testConfig = new PoolableTestConfig(1, 1,
                    Mono.fromCallable(() -> new PoolableTest(newCount.getAndIncrement()))
                            .subscribeOn(Schedulers.newParallel("poolable test allocator")));

            ThreadAffinityPool<PoolableTest> pool = new ThreadAffinityPool<>(testConfig);

            //the pool is started with one elements, and has capacity for 1.
            //we actually first borrow that element so that next borrow will wait for a release
            PooledRef<PoolableTest> uniqueSlot = pool.borrow().block();
            assertThat(uniqueSlot).isNotNull();

            //we prepare next borrow
            Mono<PooledRef<PoolableTest>> borrower = pool.borrow();
            CountDownLatch latch = new CountDownLatch(1);

            //we actually perform the borrow from its dedicated thread, capturing the thread on which the element will actually get delivered
            borrow1Scheduler.schedule(() -> borrower.subscribe(v -> threadName.set(Thread.currentThread().getName())
                    , e -> latch.countDown(), latch::countDown));

            //in parallel, we'll both attempt concurrent borrow AND release the unique element (each on their dedicated threads)
            racerBorrowScheduler.schedule(pool.borrow()::block, 100, TimeUnit.MILLISECONDS);
            racerReleaseScheduler.schedule(uniqueSlot.releaseMono()::block, 100, TimeUnit.MILLISECONDS);
            latch.await(1, TimeUnit.SECONDS);

            assertThat(newCount).as("created 1 poolable in round " + round).hasValue(1);

            //we expect that sometimes the race will let the second borrower thread drain, which would mean first borrower
            //will get the element delivered from racerBorrow thread. Yet the rest of the time it would get drained by racerRelease.
            if (threadName.get().startsWith("racerRelease")) releaserWins.incrementAndGet();
            else if (threadName.get().startsWith("racerBorrow")) borrowerWins.incrementAndGet();
            else System.out.println(threadName.get());
        }

        @Test
        void consistentThreadDeliveringWhenHasElements() throws InterruptedException {
            Scheduler deliveryScheduler = Schedulers.newSingle("delivery");
            AtomicReference<String> threadName = new AtomicReference<>();
            Scheduler borrowScheduler = Schedulers.newSingle("borrow");
            PoolableTestConfig testConfig = new PoolableTestConfig(1, 1,
                    Mono.fromCallable(PoolableTest::new)
                            .subscribeOn(Schedulers.newParallel("poolable test allocator")),
                    deliveryScheduler);
            ThreadAffinityPool<PoolableTest> pool = new ThreadAffinityPool<>(testConfig);

            //the pool is started with one available element
            //we prepare to borrow it
            Mono<PooledRef<PoolableTest>> borrower = pool.borrow();
            CountDownLatch latch = new CountDownLatch(1);

            //we actually request the borrow from a separate thread and see from which thread the element was delivered
            borrowScheduler.schedule(() -> borrower.subscribe(v -> threadName.set(Thread.currentThread().getName()), e -> latch.countDown(), latch::countDown));
            latch.await(1, TimeUnit.SECONDS);

            assertThat(threadName.get())
                    .startsWith("delivery-");
        }

        @Test
        void consistentThreadDeliveringWhenNoElementsButNotFull() throws InterruptedException {
            Scheduler deliveryScheduler = Schedulers.newSingle("delivery");
            AtomicReference<String> threadName = new AtomicReference<>();
            Scheduler borrowScheduler = Schedulers.newSingle("borrow");
            PoolableTestConfig testConfig = new PoolableTestConfig(0, 1,
                    Mono.fromCallable(PoolableTest::new)
                            .subscribeOn(Schedulers.newParallel("poolable test allocator")),
                    deliveryScheduler);
            ThreadAffinityPool<PoolableTest> pool = new ThreadAffinityPool<>(testConfig);

            //the pool is started with no elements, and has capacity for 1
            //we prepare to borrow, which would allocate the element
            Mono<PooledRef<PoolableTest>> borrower = pool.borrow();
            CountDownLatch latch = new CountDownLatch(1);

            //we actually request the borrow from a separate thread, but the allocation also happens in a dedicated thread
            //we look at which thread the element was delivered from
            borrowScheduler.schedule(() -> borrower.subscribe(v -> threadName.set(Thread.currentThread().getName()), e -> latch.countDown(), latch::countDown));
            latch.await(1, TimeUnit.SECONDS);

            assertThat(threadName.get())
                    .startsWith("delivery-");
        }

        @Test
        void consistentThreadDeliveringWhenNoElementsAndFull() throws InterruptedException {
            Scheduler deliveryScheduler = Schedulers.newSingle("delivery");
            AtomicReference<String> threadName = new AtomicReference<>();
            Scheduler borrowScheduler = Schedulers.newSingle("borrow");
            Scheduler releaseScheduler = Schedulers.fromExecutorService(
                    Executors.newSingleThreadScheduledExecutor((r -> new Thread(r,"release"))));
            PoolableTestConfig testConfig = new PoolableTestConfig(1, 1,
                    Mono.fromCallable(PoolableTest::new)
                            .subscribeOn(Schedulers.newParallel("poolable test allocator")),
                    deliveryScheduler);
            ThreadAffinityPool<PoolableTest> pool = new ThreadAffinityPool<>(testConfig);

            //the pool is started with one elements, and has capacity for 1.
            //we actually first borrow that element so that next borrow will wait for a release
            PooledRef<PoolableTest> uniqueSlot = pool.borrow().block();
            assertThat(uniqueSlot).isNotNull();

            //we prepare next borrow
            Mono<PooledRef<PoolableTest>> borrower = pool.borrow();
            CountDownLatch latch = new CountDownLatch(1);

            //we actually perform the borrow from its dedicated thread, capturing the thread on which the element will actually get delivered
            borrowScheduler.schedule(() -> borrower.subscribe(v -> threadName.set(Thread.currentThread().getName()),
                    e -> latch.countDown(), latch::countDown));
            //after a short while, we release the borrowed unique element from a third thread
            releaseScheduler.schedule(uniqueSlot.releaseMono()::block, 500, TimeUnit.MILLISECONDS);
            latch.await(1, TimeUnit.SECONDS);

            assertThat(threadName.get())
                    .startsWith("delivery-");
        }

        @Test
        @Tag("loops")
        void consistentThreadDeliveringWhenNoElementsAndFullAndRaceDrain_loop() throws InterruptedException {
            for (int i = 0; i < 100; i++) {
                consistentThreadDeliveringWhenNoElementsAndFullAndRaceDrain(i);
            }
        }

        @Test
        void consistentThreadDeliveringWhenNoElementsAndFullAndRaceDrain() throws InterruptedException {
            consistentThreadDeliveringWhenNoElementsAndFullAndRaceDrain(0);
        }

        void consistentThreadDeliveringWhenNoElementsAndFullAndRaceDrain(int i) throws InterruptedException {
            Scheduler deliveryScheduler = Schedulers.newSingle("delivery");
            AtomicReference<String> threadName = new AtomicReference<>();
            AtomicInteger newCount = new AtomicInteger();

            Scheduler borrow1Scheduler = Schedulers.newSingle("borrow1");
            Scheduler racerReleaseScheduler = Schedulers.fromExecutorService(
                    Executors.newSingleThreadScheduledExecutor((r -> new Thread(r,"racerRelease"))));
            Scheduler racerBorrowScheduler = Schedulers.newSingle("racerBorrow");

            PoolableTestConfig testConfig = new PoolableTestConfig(1, 1,
                    Mono.fromCallable(() -> new PoolableTest(newCount.getAndIncrement()))
                            .subscribeOn(Schedulers.newParallel("poolable test allocator")),
                    deliveryScheduler);
            ThreadAffinityPool<PoolableTest> pool = new ThreadAffinityPool<>(testConfig);

            //the pool is started with one elements, and has capacity for 1.
            //we actually first borrow that element so that next borrow will wait for a release
            PooledRef<PoolableTest> uniqueSlot = pool.borrow().block();
            assertThat(uniqueSlot).isNotNull();

            //we prepare next borrow
            Mono<PooledRef<PoolableTest>> borrower = pool.borrow();
            CountDownLatch latch = new CountDownLatch(1);

            //we actually perform the borrow from its dedicated thread, capturing the thread on which the element will actually get delivered
            borrow1Scheduler.schedule(() -> borrower.subscribe(v -> threadName.set(Thread.currentThread().getName())
                    , e -> latch.countDown(), latch::countDown));

            //in parallel, we'll both attempt a second borrow AND release the unique element (each on their dedicated threads
            Mono<PooledRef<PoolableTest>> otherBorrower = pool.borrow();
            racerBorrowScheduler.schedule(() -> otherBorrower.subscribe().dispose(), 100, TimeUnit.MILLISECONDS);
            racerReleaseScheduler.schedule(uniqueSlot.releaseMono()::block, 100, TimeUnit.MILLISECONDS);
            latch.await(1, TimeUnit.SECONDS);

            //we expect that, consistently, the poolable is delivered on a `delivery` thread
            assertThat(threadName.get()).as("round #" + i).startsWith("delivery-");

            //we expect that only 1 element was created
            assertThat(newCount).as("elements created in round " + i).hasValue(1);
        }
    }

    @Test
    void disposingPoolDisposesElements() {
        AtomicInteger cleanerCount = new AtomicInteger();
        ThreadAffinityPool<PoolableTest> pool = new ThreadAffinityPool<>(new DefaultPoolConfig<>(0, 3, Mono.fromCallable(PoolableTest::new),
                p -> Mono.fromRunnable(cleanerCount::incrementAndGet),
                slot -> !slot.poolable().isHealthy(), null));

        PoolableTest pt1 = new PoolableTest(1);
        PoolableTest pt2 = new PoolableTest(2);
        PoolableTest pt3 = new PoolableTest(3);

        pool.available_mpmc.offer(new ThreadAffinityPool.TAPooledRef<>(pool, pt1));
        pool.available_mpmc.offer(new ThreadAffinityPool.TAPooledRef<>(pool, pt2));
        pool.available_mpmc.offer(new ThreadAffinityPool.TAPooledRef<>(pool, pt3));

        pool.dispose();

        assertThat(pool.available_mpmc).isEmpty();
        assertThat(cleanerCount).as("recycled elements").hasValue(0);
        assertThat(pt1.isDisposed()).as("pt1 disposed").isTrue();
        assertThat(pt2.isDisposed()).as("pt2 disposed").isTrue();
        assertThat(pt3.isDisposed()).as("pt3 disposed").isTrue();
    }

    @Test
    void disposingPoolFailsPendingBorrowers() {
        AtomicInteger cleanerCount = new AtomicInteger();
        ThreadAffinityPool<PoolableTest> pool = new ThreadAffinityPool<>(new DefaultPoolConfig<>(3, 3, Mono.fromCallable(PoolableTest::new),
                p -> Mono.fromRunnable(cleanerCount::incrementAndGet),
                slot -> !slot.poolable().isHealthy(), null));

        PooledRef<PoolableTest> slot1 = pool.borrow().block();
        PooledRef<PoolableTest> slot2 = pool.borrow().block();
        PooledRef<PoolableTest> slot3 = pool.borrow().block();
        assertThat(slot1).as("slot1").isNotNull();
        assertThat(slot2).as("slot2").isNotNull();
        assertThat(slot3).as("slot3").isNotNull();

        PoolableTest borrowed1 = slot1.poolable();
        PoolableTest borrowed2 = slot2.poolable();
        PoolableTest borrowed3 = slot3.poolable();


        AtomicReference<Throwable> borrowerError = new AtomicReference<>();
        Mono<PooledRef<PoolableTest>> pendingBorrower = pool.borrow();
        pendingBorrower.subscribe(v -> fail("unexpected value " + v),
                borrowerError::set);

        pool.dispose();

        assertThat(pool.available_mpmc).isEmpty();
        assertThat(cleanerCount).as("recycled elements").hasValue(0);
        assertThat(borrowed1.isDisposed()).as("borrowed1 held").isFalse();
        assertThat(borrowed2.isDisposed()).as("borrowed2 held").isFalse();
        assertThat(borrowed3.isDisposed()).as("borrowed3 held").isFalse();
        assertThat(borrowerError.get()).hasMessage("Pool has been shut down");
    }

    @Test
    void releasingToDisposedPoolDisposesElement() {
        AtomicInteger cleanerCount = new AtomicInteger();
        ThreadAffinityPool<PoolableTest> pool = new ThreadAffinityPool<>(new DefaultPoolConfig<>(3, 3, Mono.fromCallable(PoolableTest::new),
                p -> Mono.fromRunnable(cleanerCount::incrementAndGet),
                slot -> !slot.poolable().isHealthy(), null));

        PooledRef<PoolableTest> slot1 = pool.borrow().block();
        PooledRef<PoolableTest> slot2 = pool.borrow().block();
        PooledRef<PoolableTest> slot3 = pool.borrow().block();

        assertThat(slot1).as("slot1").isNotNull();
        assertThat(slot2).as("slot2").isNotNull();
        assertThat(slot3).as("slot3").isNotNull();

        pool.dispose();

        assertThat(pool.available_mpmc).isEmpty();

        slot1.releaseMono().block();
        slot2.releaseMono().block();
        slot3.releaseMono().block();

        assertThat(cleanerCount).as("recycled elements").hasValue(0);
        assertThat(slot1.poolable().isDisposed()).as("borrowed1 disposed").isTrue();
        assertThat(slot2.poolable().isDisposed()).as("borrowed2 disposed").isTrue();
        assertThat(slot3.poolable().isDisposed()).as("borrowed3 disposed").isTrue();
    }

    @Test
    void borrowingFromDisposedPoolFailsBorrower() {
        AtomicInteger cleanerCount = new AtomicInteger();
        ThreadAffinityPool<PoolableTest> pool = new ThreadAffinityPool<>(new DefaultPoolConfig<>(0, 3, Mono.fromCallable(PoolableTest::new),
                p -> Mono.fromRunnable(cleanerCount::incrementAndGet),
                slot -> !slot.poolable().isHealthy(), null));

        assertThat(pool.available_mpmc).isEmpty();

        pool.dispose();

        StepVerifier.create(pool.borrow())
                .verifyErrorMessage("Pool has been shut down");

        assertThat(cleanerCount).as("recycled elements").hasValue(0);
    }

    @Test
    void poolIsDisposed() {
        ThreadAffinityPool<PoolableTest> pool = new ThreadAffinityPool<>(new DefaultPoolConfig<>(0, 3,
                Mono.fromCallable(PoolableTest::new), p -> Mono.empty(),
                slot -> !slot.poolable().isHealthy(), null));

        assertThat(pool.isDisposed()).as("not yet disposed").isFalse();

        pool.dispose();

        assertThat(pool.isDisposed()).as("disposed").isTrue();
    }

    @Test
    void disposingPoolClosesCloseable() {
        Formatter uniqueElement = new Formatter();

        ThreadAffinityPool<Formatter> pool = new ThreadAffinityPool<>(new DefaultPoolConfig<>(1, 1,
                Mono.just(uniqueElement),
                f -> Mono.empty(),
                f -> true, null));

        pool.dispose();

        assertThatExceptionOfType(FormatterClosedException.class)
                .isThrownBy(uniqueElement::flush);
    }

    @Test
    void allocatorErrorOutsideConstructorIsPropagated() {
        ThreadAffinityPool<String> pool = new ThreadAffinityPool<>(new DefaultPoolConfig<>(0, 1,
                Mono.error(new IllegalStateException("boom")),
                f -> Mono.empty(),
                f -> true, null));

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(pool.borrow()::block)
                .withMessage("boom");
    }

    @Test
    void allocatorErrorInConstructorIsThrown() {
        DefaultPoolConfig<Object> config = new DefaultPoolConfig<>(1, 1,
                Mono.error(new IllegalStateException("boom")),
                f -> Mono.empty(),
                f -> true, null);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new ThreadAffinityPool<>(config))
                .withMessage("boom");
    }

    @Test
    void discardCloseableWhenCloseFailureLogs() {
        TestLogger testLogger = new TestLogger();
        Loggers.useCustomLoggers(it -> testLogger);
        try {
            Closeable closeable = () -> {
                throw new IOException("boom");
            };

            ThreadAffinityPool<Closeable> pool = new ThreadAffinityPool<>(new DefaultPoolConfig<>(1, 1,
                    Mono.just(closeable),
                    f -> Mono.empty(),
                    f -> true, null));

            pool.dispose();

            assertThat(testLogger.getOutContent())
                    .contains("Failure while discarding a released Poolable that is Closeable, could not close - java.io.IOException: boom");
        }
        finally {
            Loggers.resetLoggerFactory();
        }
    }
}