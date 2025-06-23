package moe.tachyon.quiz.utils

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

class Locks<K>
{
    val data = hashMapOf<K, PhantomReference<Lock<K>>>()
    private val mutex = Mutex()

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
                    }
                }
            }.start()
        }
    }

    class Lock<K>(val locks: Locks<K>, val id: K): Mutex by Mutex()
    class LockReference<K>(lock: Lock<K>): PhantomReference<Lock<K>>(lock, queue)
    {
        val id = lock.id
        val locks = lock.locks
    }

    suspend fun getLock(key: K): Lock<K> = mutex.withLock()
    {
        data[key]?.get()?.let { return it }
        val newLock = Lock(this, key)
        data[key] = LockReference(newLock)
        return newLock
    }

    @OptIn(ExperimentalContracts::class)
    suspend inline fun <R> withLock(key: K, block: ()->R): R
    {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        return getLock(key).withLock { block() }
    }

    @OptIn(ExperimentalContracts::class)
    suspend inline fun <R> tryWithLock(key: K, fail: ()->R, block: ()->R): R
    {
        contract {
            callsInPlace(fail, InvocationKind.AT_MOST_ONCE)
            callsInPlace(block, InvocationKind.AT_MOST_ONCE)
        }
        val lock = getLock(key)
        if (!lock.tryLock()) return fail()
        return try
        {
            block()
        }
        finally
        {
            lock.unlock()
        }
    }
}