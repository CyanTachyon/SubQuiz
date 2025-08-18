package moe.tachyon.quiz.utils

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import kotlin.contracts.ExperimentalContracts
import kotlin.coroutines.CoroutineContext

class Locks<K>
{
    private val data = hashMapOf<K, LockReference<K>>()
    private val mutex = ReentrantLock()

    companion object
    {
        private val queue = ReferenceQueue<Lock<*>>()

        init
        {
            @Suppress("OPT_IN_USAGE")
            GlobalScope.launch()
            {
                while (true)
                {
                    val ref = runCatching { queue.remove() }.getOrNull() ?: continue
                    ref as LockReference<*>
                    ref.locks.mutex.withLock()
                    {
                        ref.locks.data.remove(ref.id)
                        ref.clear()
                    }
                }
            }.start()
        }
    }

    private class Lock<K>(val locks: Locks<K>, val id: K): ReentrantLock()
    private class LockReference<K>(lock: Lock<K>): WeakReference<Lock<K>>(lock, queue)
    {
        val id = lock.id
        val locks = lock.locks
    }

    suspend fun getLock(key: K): ReentrantLock = mutex.withLock()
    {
        data[key]?.get()?.let { return@withLock it }
        val newLock = Lock(this, key)
        data[key] = LockReference(newLock)
        return@withLock newLock
    }

    @OptIn(ExperimentalContracts::class)
    suspend fun <R> withLock(key: K, block: suspend ()->R): R
    {
        return getLock(key).withLock { block() }
    }

    @OptIn(ExperimentalContracts::class)
    suspend fun <R> tryWithLock(key: K, fail: suspend ()->R, block: suspend ()->R): R
    {
        val lock = getLock(key)
        return lock.tryWithLock(fail, block)
    }
}

/**
 * 一个特殊的Mutex，如果对同一进程重复获取锁，则不会阻塞。
 */
open class ReentrantLock
{
    private data class LockContext(private val lock: ReentrantLock): CoroutineContext.Element, CoroutineContext.Key<LockContext>
    {
        override val key: CoroutineContext.Key<LockContext> = this
        override fun equals(other: Any?): Boolean = lock === (other as? LockContext)?.lock
        override fun hashCode(): Int = System.identityHashCode(lock)
        override fun toString(): String = "LockContext($lock)"
    }

    private val mutex = Mutex()
    private val context = LockContext(this)
    val isLocked: Boolean get() = mutex.isLocked
    suspend fun <T> withLock(block: suspend ()->T): T
    {
        if (currentCoroutineContext()[context] != null) return block()
        return mutex.withLock()
        {
            safeWithContext(context) { block() }
        }
    }
    suspend fun <R> tryWithLock(fail: suspend ()->R, block: suspend ()->R): R
    {
        if (currentCoroutineContext()[context] != null) return block()
        if (!mutex.tryLock()) return fail()
        try
        {
            return safeWithContext(context) { block() }
        }
        finally
        {
            mutex.unlock()
        }
    }
}

/**
 * 当context被取消，即使内部的程序捕捉取消事件并继续执行，withContext也会抛出CancellationException，
 * 该函数在这种情况下不会抛出异常，而是返回内部的结果。
 */
suspend fun <T> safeWithContext(
    context: CoroutineContext,
    block: suspend CoroutineScope.() -> T
): T
{
    var res: Result<T>? = null
    val res1 = runCatching()
    {
        withContext(context)
        {
            res = runCatching { block() }
            return@withContext res
        }
    }
    if (res != null) return res.getOrThrow()

    res1.getOrThrow().getOrThrow()

    error("unreachable")
}