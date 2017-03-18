// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import org.hamcrest.Matcher;
import org.junit.Assert;

import static org.hamcrest.CoreMatchers.instanceOf;

enum AsyncAssert {
	;

	public static CompletableFuture<Void> cancelsAsync(@NotNull Supplier<CompletableFuture<?>> supplier) {
		// Shouldn't throw (operation is expected to return a failed future)
		CompletableFuture<?> future = supplier.get();
		return future.handle((result, exception) -> {
			Assert.assertTrue("Operation should be cancelled", future.isCancelled());
			Assert.assertTrue("Operation should have failed", future.isCompletedExceptionally());
			Assert.assertThat(exception, instanceOf(CancellationException.class));
			return null;
		});
	} 

	public static CompletableFuture<Void> throwsAsync(@NotNull Class<? extends Throwable> exceptionClass, @NotNull Supplier<CompletableFuture<?>> supplier) {
		return throwsAsync(instanceOf(exceptionClass), supplier);
	} 

	public static CompletableFuture<Void> throwsAsync(@NotNull Matcher<? super Throwable> matcher, @NotNull Supplier<CompletableFuture<?>> supplier) {
		// Shouldn't throw (operation is expected to return a failed future)
		CompletableFuture<?> future = supplier.get();
		return future.handle((result, exception) -> {
			Assert.assertFalse("Operation should not be cancelled", future.isCancelled());
			Assert.assertTrue("Operation should have failed", future.isCompletedExceptionally());
			Assert.assertThat(exception, instanceOf(CompletionException.class));
			Assert.assertThat(exception.getCause(), matcher);
			return null;
		});
	} 
}
