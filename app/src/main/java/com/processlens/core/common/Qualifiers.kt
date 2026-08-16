package com.processlens.core.common

import javax.inject.Qualifier

/**
 * Hilt qualifiers for the coroutine dispatchers. Injecting dispatchers rather
 * than calling `Dispatchers.IO` directly is what makes the repository and
 * observer layers testable with `runTest`'s scheduler (Section 56), and it keeps
 * every /proc read and shell invocation provably off the main thread
 * (Section 43).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

/** Application-scoped supervisor scope for work that must outlive a ViewModel. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
