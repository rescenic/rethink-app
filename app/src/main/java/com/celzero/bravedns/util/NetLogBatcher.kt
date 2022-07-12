/*
 * Copyright 2022 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.celzero.bravedns.util

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

// channel buffer receives batched entries of batchsize or once every waitms from a batching
// producer or a time-based monitor (signal) running in a single-threaded co-routine context.
class NetLogBatcher<T>(val scope: CoroutineScope, val processor: suspend (List<T>) -> Unit) {
    // i keeps track of currently in-use buffer
    var lsn = 0

    // a single thread to run sig and batch co-routines in;
    // to avoid use of mutex/semaphores over shared-state
    @OptIn(DelicateCoroutinesApi::class) val looper = newSingleThreadContext("netlogprovider")

    private val n1 = CoroutineName("producer")
    private val n2 = CoroutineName("signal")

    // dispatch buffer to consumer if greater than batch size
    private val batchSize = 20

    // no of batch-sized buffers to hold in a channel
    private val qsize = 2

    // wait time before dispatching a batch, regardless of its size
    // signal waits min waitms and max waitms*2
    private val waitms = 2500L

    // buffer channel, holds at most 2 buffers, and drops the oldest
    private val buffers = Channel<List<T>>(qsize, BufferOverflow.DROP_OLDEST)

    // signal channel, holds at most 1 signal, and drops the oldest
    private val signal = Channel<Int>(Channel.Factory.CONFLATED)

    // batches[0] and batches[1] take turns at batching
    var batches = mutableListOf<T>()

    init {
        begin()
        monitorCancellation()
    }

    private fun begin() = scope.launch {
        // init sig
        sig()
        consume()
    }

    // stackoverflow.com/a/68905423
    private fun monitorCancellation() = scope.launch {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                signal.close()
                buffers.close()
                looper.close()
            }
        }
    }

    private fun consume() = scope.launch(Dispatchers.IO) {
        for (y in buffers) {
            processor(y)
        }
    }

    private suspend fun txswap() {
        val b = batches
        batches = mutableListOf<T>()
        Log.i(LoggerConstants.LOG_TAG_VPN, "transfer and swap (${lsn}) ${b.size}")
        lsn = (lsn + 1) // swap buffers
        buffers.send(b)
    }

    fun add(payload: T) = scope.launch(looper + n1) {
        batches.add(payload)
        // if the batch size is met, dispatch it to the consumer
        if (batches.size >= batchSize) {
            txswap()
        } else if (batches.size == 1) {
            signal.send(lsn) // start tracking 'i'
        }
    }

    private fun sig() = scope.launch(looper + n2) {
        // consume all signals
        for (tracklsn in signal) {
            // do not honor the signal for 'l' if a[l] is empty
            // this can happen if the signal for 'l' is processed
            // after the fact that 'l' has been swapped out by 'batch'
            if (batches.size <= 0) {
                Log.d(LoggerConstants.LOG_TAG_VPN, "signal continue for buffer")
                println("signal continue for buffer")
                continue
            } else {
                Log.d(LoggerConstants.LOG_TAG_VPN, "signal sleep for $waitms for buffer")
            }

            // wait for 'batch' to dispatch
            delay(waitms)
            Log.d(LoggerConstants.LOG_TAG_VPN,
                  "signal wait over for buf, sz(${batches.size}) / cur-buf(${lsn})")

            // 'l' is the current buffer, that is, 'l == i',
            // and 'batch' hasn't dispatched it,
            // but time's up...
            if (lsn == tracklsn && batches.size > 0) {
                txswap()
            }
        }
    }
}