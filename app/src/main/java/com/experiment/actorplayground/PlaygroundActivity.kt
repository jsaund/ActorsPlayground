package com.experiment.actorplayground

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.CompletableDeferred
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.channels.SendChannel
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.channels.consumeEach
import kotlinx.coroutines.experimental.launch

class PlaygroundActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "PlaygroundActivity"
    }

    private lateinit var thread: TextView
    private lateinit var message: TextView
    private lateinit var time: TextView
    private lateinit var startNaive: Button
    private lateinit var startOptimized: Button
    private lateinit var startDistributed: Button

    private lateinit var job: Job

    private var actor: SendChannel<Action>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playground)

        thread = findViewById(R.id.tv_thread)
        message = findViewById(R.id.tv_message)
        time = findViewById(R.id.tv_time)
        startNaive = findViewById(R.id.btn_start)
        startOptimized = findViewById(R.id.btn_start_optimized)
        startDistributed = findViewById(R.id.btn_start_distributed)

        thread.text = getString(R.string.thread, "n/a")
        message.text = getString(R.string.message, "n/a")
        time.text = getString(R.string.time, 0)

        startNaive.setOnClickListener { startNaive() }
        startOptimized.setOnClickListener { startOptimized() }
        startDistributed.setOnClickListener { startDistributed() }

        job = Job()
    }

    override fun onDestroy() {
        super.onDestroy()

        job.cancel()
        actor?.close()
    }

    private fun startNaive() {
        job.cancel()
        job = Job()

        actor?.close()
        actor = createActor()

        // Create 1000 Spin messages and dispatch them to the Actor
        val start = System.currentTimeMillis()
        (1..1000).map { Spin(it) }
                .forEach { s ->
                    launch(UI, parent = job) { actor!!.send(s) }
                    val duration = System.currentTimeMillis() - start
                    thread.text = getString(R.string.thread, Thread.currentThread().name)
                    message.text = getString(R.string.message, s.id.toString())
                    time.text = getString(R.string.time, duration)
                    Log.d(TAG, "[thread=${Thread.currentThread().name}] [${s.id}] time=$duration")
                }
        Log.d(TAG, "time=${System.currentTimeMillis() - start}")
    }

    private fun startOptimized() {
        job.cancel()
        job = Job()

        actor?.close()
        actor = createActor()

        launch(UI, parent = job) {
            val start = System.currentTimeMillis()
            val ack = CompletableDeferred<Boolean>()
            (1..1001).map { if (it == 1001) Done(ack) else Spin(it) }
                    .forEach { s ->
                        actor!!.send(s)
                        val duration = System.currentTimeMillis() - start
                        thread.text = getString(R.string.thread, Thread.currentThread().name)
                        message.text = when (s) {
                            is Spin -> getString(R.string.message, s.toString())
                            is Done -> getString(R.string.message, "Done")
                        }
                        time.text = getString(R.string.time, duration)
                        Log.d(TAG, "[thread=${Thread.currentThread().name}] [$s] time=$duration")
                    }
            // Wait for the Actor to trigger completion of the Done operation
            ack.await()
            Log.d(TAG, "time=${System.currentTimeMillis() - start}")
        }
    }

    private fun startDistributed() {
        job.cancel()
        job = Job()

        actor?.close()

        val children: List<SendChannel<Action>> = (1..4).map { createActor() }
        var next = 0

        actor = actor(CommonPool, 0) {
            consumeEach { action ->
                when (action) {
                    is Spin -> {
                        children[next++ % children.size].send(action)
                    }
                    is Done -> {
                        val acks = (1..children.size)
                                .map { CompletableDeferred<Boolean>() }

                        acks
                                .withIndex()
                                .forEach { (index, ack) ->
                                    children[index].send(Done(ack))
                                }

                        acks.map { it.await() }
                        action.ack.complete(true)
                    }
                }
            }
        }

        job = launch(UI) {
            val start = System.currentTimeMillis()
            val ack = CompletableDeferred<Boolean>()
            (1..1001).map { if (it == 1001) Done(ack) else Spin(it) }
                    .forEach { s ->
                        actor!!.send(s)
                        val duration = System.currentTimeMillis() - start
                        thread.text = getString(R.string.thread, Thread.currentThread().name)
                        message.text = when (s) {
                            is Spin -> getString(R.string.message, s.toString())
                            is Done -> getString(R.string.message, "Done")
                        }
                        time.text = getString(R.string.time, duration)
                        Log.d(TAG, "[thread=${Thread.currentThread().name}] [$s] time=$duration")
                    }
            // Wait for the Actor to trigger completion of the Done operation
            ack.await()
            Log.d(TAG, "time=${System.currentTimeMillis() - start}")
        }
    }

    private fun createActor() = actor<Action>(CommonPool, 0, parent = job) {
        consumeEach { action ->
            when (action) {
                is Spin -> spin(action.id)
                is Done -> action.ack.complete(true)
            }
        }
    }

    /** Spin for at least {@param durationMillis} period of time */
    private fun spin(value: Int, durationMillis: Int = 10): Int {
        val startMillis = System.currentTimeMillis()
        while (System.currentTimeMillis() - startMillis < durationMillis) {
        }
        Log.d(TAG, "[thread=${Thread.currentThread().name}] [$value] processed")
        return value
    }
}
