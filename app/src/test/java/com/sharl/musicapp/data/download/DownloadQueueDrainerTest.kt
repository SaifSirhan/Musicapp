package com.sharl.musicapp.data.download

import com.sharl.musicapp.data.DownloadJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueDrainerTest {

    private class FakeStore : DownloadJobStore {
        val jobs = LinkedHashMap<String, DownloadJob>()

        override suspend fun get(videoId: String) = jobs[videoId]
        override suspend fun save(job: DownloadJob) { jobs.putIfAbsent(job.videoId, job) }
        override suspend fun bindDestination(videoId: String, destinationDir: String) {}
        override suspend fun claim(job: DownloadJob, now: Long) = job
        override suspend fun finish(
            videoId: String, status: String, attempts: Int, updatedAt: Long, lastError: String?
        ) = 1
        override suspend fun setStatus(
            videoId: String, status: String, attempts: Int, updatedAt: Long, lastError: String?
        ) { jobs[videoId] = jobs.getValue(videoId).copy(status = status) }
        override suspend fun resumable() = jobs.values.filter {
            it.status == DownloadJob.Status.QUEUED || it.status == DownloadJob.Status.WAITING_FOR_SPACE
        }
        override suspend fun completed() = jobs.values.filter { it.status == DownloadJob.Status.COMPLETED }
        override suspend fun requeueRunning(updatedAt: Long): Int {
            var count = 0
            jobs.replaceAll { _, job ->
                if (job.status != DownloadJob.Status.DOWNLOADING) job
                else { count++; job.copy(status = DownloadJob.Status.QUEUED) }
            }
            return count
        }
        override suspend fun delete(videoId: String) { jobs.remove(videoId) }
    }

    private fun job(videoId: String, status: String = DownloadJob.Status.QUEUED) =
        DownloadJob(videoId, "Title $videoId", "Artist", null, 1000L, status, 0, 1L, 1L)

    @Test
    fun drainConsumesEveryQueuedJob() = runBlocking {
        val store = FakeStore().apply {
            jobs["a"] = job("a"); jobs["b"] = job("b"); jobs["c"] = job("c")
        }
        val attempted = ArrayList<String>()
        val drainer = DownloadQueueDrainer(store, recover = {}) { transfer ->
            attempted += transfer.videoId
            store.delete(transfer.videoId)
            DrainVerdict.PROCEEDED
        }

        assertTrue(drainer.drain())
        assertEquals(listOf("a", "b", "c"), attempted)
        assertTrue(store.jobs.isEmpty())
    }

    @Test
    fun blockedJobStopsTheRestOfTheDrain() = runBlocking {
        val store = FakeStore().apply {
            jobs["a"] = job("a"); jobs["b"] = job("b")
        }
        val attempted = ArrayList<String>()
        val drainer = DownloadQueueDrainer(store, recover = {}) { transfer ->
            attempted += transfer.videoId
            if (transfer.videoId == "a") DrainVerdict.BLOCKED
            else { store.delete(transfer.videoId); DrainVerdict.PROCEEDED }
        }

        assertFalse(drainer.drain())
        assertEquals(listOf("a"), attempted)
        assertEquals(listOf("a", "b"), store.jobs.keys.toList())
    }

    @Test
    fun failedJobDoesNotStrandTheQueue() = runBlocking {
        val store = FakeStore().apply {
            jobs["a"] = job("a"); jobs["b"] = job("b")
        }
        val drainer = DownloadQueueDrainer(store, recover = {}) { transfer ->
            if (transfer.videoId == "a") store.setStatus(transfer.videoId, DownloadJob.Status.FAILED, 1, 2L, "broken")
            else store.delete(transfer.videoId)
            DrainVerdict.PROCEEDED
        }

        assertTrue(drainer.drain())
        assertEquals(DownloadJob.Status.FAILED, store.jobs.getValue("a").status)
        assertTrue(store.resumable().isEmpty())
    }

    @Test
    fun recoveryRunsBeforeTheFirstTransfer() = runBlocking {
        val order = ArrayList<String>()
        val store = FakeStore().apply { jobs["a"] = job("a", DownloadJob.Status.DOWNLOADING) }
        val drainer = DownloadQueueDrainer(
            store,
            recover = { order += "recover"; store.requeueRunning(2L) }
        ) { transfer ->
            order += "transfer ${transfer.videoId}"
            store.delete(transfer.videoId)
            DrainVerdict.PROCEEDED
        }

        assertTrue(drainer.drain())
        assertEquals(listOf("recover", "transfer a"), order)
    }

    @Test
    fun cancellationReachesTheCaller() = runBlocking {
        val store = FakeStore().apply { jobs["a"] = job("a") }
        val drainer = DownloadQueueDrainer(store, recover = {}) {
            throw CancellationException("service destroyed")
        }

        var cancelled = false
        try {
            drainer.drain()
        } catch (e: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(DownloadJob.Status.QUEUED, store.jobs.getValue("a").status)
    }
}
