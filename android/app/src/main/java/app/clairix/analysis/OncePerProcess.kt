package app.clairix.analysis

/**
 * An answer looked up once per process and kept, for a fact that cannot change while
 * the process lives but is asked for by a check that runs every half minute. A lookup
 * that fails (returns null) is tried again on the next [get]; one that answers is never
 * repeated. Two threads asking at the same moment may both look it up, and both get
 * the answer.
 */
internal class OncePerProcess<T : Any>(private val lookup: () -> T?) {

    @Volatile private var answer: T? = null

    fun get(): T? = answer ?: lookup()?.also { answer = it }
}
