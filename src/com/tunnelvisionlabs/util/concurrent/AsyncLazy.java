// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * A thread-safe, lazily and asynchronously evaluated value factory.
 *
 * <p>Copied from Microsoft/vs-threading@14f77875.</p>
 *
 * @param <T> The type of value generated by the value factory.
 */
public class AsyncLazy<T> {
	/**
	 * The value set to the {@link #recursiveFactoryCheck} field while the value factory is executing.
	 */
	private static final Object RECURSIVE_CHECK_SENTINEL = new Object();

	/**
	 * The object to lock to provide thread-safety.
	 */
	private final ReentrantLock syncObject = new ReentrantLock();

	/**
	 * The unique instance identifier.
	 */
	private final AsyncLocal<Object> recursiveFactoryCheck = new AsyncLocal<>();

	/**
	 * The function to invoke to produce the future.
	 */
	private final AtomicReference<Supplier<? extends CompletableFuture<? extends T>>> valueFactory = new AtomicReference<>();

	/**
	 * The async pump to join on calls to {@link #getValueAsync()}.
	 */
	private JoinableFutureFactory jobFactory;

	/**
	 * The result of the value factory.
	 */
	private CompletableFuture<? extends T> value;

	/**
	 * A joinable future whose result is the value to be cached.
	 */
	private JoinableFuture<? extends T> joinableTask;

	/**
	 * Constructs a new instance of the {@link AsyncLazy} class.
	 *
	 * @param valueFactory The async function that produces the value. To be invoked at most once.
	 */
	public AsyncLazy(@NotNull Supplier<? extends CompletableFuture<? extends T>> valueFactory) {
		this(valueFactory, null);
	}

	/**
	 * Constructs a new instance of the {@link AsyncLazy} class.
	 *
	 * @param valueFactory The async function that produces the value. To be invoked at most once.
	 * @param joinableFutureFactory The factory to use when invoking the value factory in {@link #getValueAsync()} to
	 * avoid deadlocks when the main thread is required by the value factory.
	 */
	public AsyncLazy(@NotNull Supplier<? extends CompletableFuture<? extends T>> valueFactory, @Nullable JoinableFutureFactory joinableFutureFactory) {
		Requires.notNull(valueFactory, "valueFactory");
		this.valueFactory.set(valueFactory);
		this.jobFactory = joinableFutureFactory;
	}

	/**
	 * Gets a value indicating whether the value factory has been invoked.
	 */
	public final boolean isValueCreated() {
		return this.valueFactory.get() == null;
	}

	/**
	 * Gets a value indicating whether the value factory has been invoked and has run to completion.
	 */
	public final boolean isValueFactoryCompleted() {
		return this.value != null && this.value.isDone();
	}

	@NotNull
	public final CompletableFuture<? extends T> getValueAsync(@Nullable CompletableFuture<?> cancellationFuture) {
		return ThreadingTools.withCancellation(getValueAsync(), cancellationFuture);
	}

	/**
	 * Gets the future that produces or has produced the value.
	 *
	 * @return A future whose result is the lazily constructed value.
	 * @exception IllegalStateException If the value factory calls {@link #getValueAsync()} on this instance.
	 */
	public final CompletableFuture<? extends T> getValueAsync() {
		if (!((this.value != null && this.value.isDone()) || this.recursiveFactoryCheck.getValue() == null)) {
			// PERF: we check the condition and *then* retrieve the string resource only on failure
			// because the string retrieval has shown up as significant on ETL traces.
			Verify.failOperation("ValueFactoryReentrancy");
		}

		if (this.value == null) {
			if (this.syncObject.isHeldByCurrentThread()) {
				// PERF: we check the condition and *then* retrieve the string resource only on failure
				// because the string retrieval has shown up as significant on ETL traces.
				Verify.failOperation("ValueFactoryReentrancy");
			}

			final StrongBox<InlineResumable> resumableAwaiter = new StrongBox<>();
			syncObject.lock();
			try {
				// Note that if multiple threads hit getValueAsync() before
				// the valueFactory has completed its synchronous execution,
				// then only one thread will execute the valueFactory while the
				// other threads synchronously block till the synchronous portion
				// has completed.
				if (this.value == null) {
					resumableAwaiter.set(new InlineResumable());
					Supplier<? extends CompletableFuture<? extends T>> originalValueFactory = this.valueFactory.getAndSet(null);
					Supplier<? extends CompletableFuture<? extends T>> localValueFactory =
						() -> Async.awaitAsync(
							resumableAwaiter.get(),
							() -> Async.awaitAsync(originalValueFactory.get(), false));

					this.recursiveFactoryCheck.setValue(RECURSIVE_CHECK_SENTINEL);
					try {
						if (this.jobFactory != null) {
							// Wrapping with runAsync allows a future caller
							// to synchronously block the Main thread waiting for the result
							// without leading to deadlocks.
							this.joinableTask = this.jobFactory.runAsync(localValueFactory);
							this.value = this.joinableTask.getFuture();
							this.value.whenComplete((result, exception) -> {
								jobFactory = null;
								joinableTask = null;
							});
						} else {
							this.value = localValueFactory.get();
						}
					} finally {
						this.recursiveFactoryCheck.setValue(null);
					}
				}
			} finally {
				syncObject.unlock();
			}

			// Allow the original value factory to actually run.
			if (resumableAwaiter.get() != null) {
				resumableAwaiter.get().resume();
			}
		}

		if (!value.isDone()) {
			JoinableFuture<?> future = this.joinableTask;
			if (future != null) {
				TplExtensions.forget(future.joinAsync());
			}
		}

		return Futures.nonCancellationPropagating(this.value);
	}

	/**
	 * Renders a string describing an uncreated value, or the string representation of the created value.
	 */
	@Override
	public String toString() {
		return (value != null && value.isDone())
			? (value.isDone() && !value.isCompletedExceptionally() ? value.join().toString() : "LazyValueFaulted")
			: "LazyValueNotCreated";
	}

}
