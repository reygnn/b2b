package com.github.reygnn.b2b.domain.model

/**
 * Result type crossing layer boundaries. We never throw across layers — every
 * operation that can fail returns an Outcome and the caller decides.
 */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>

    sealed interface Error : Outcome<Nothing> {
        data object Unauthenticated : Error
        data object NotPremium : Error
        data object NoActiveDevice : Error
        data object Network : Error
        data class RateLimited(val retryAfterSeconds: Int) : Error
        data class Unknown(val message: String? = null) : Error
    }
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Error -> this
}

fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Success)?.value
