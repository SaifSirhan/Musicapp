package com.sharl.musicapp.data.download

import com.sharl.musicapp.data.DownloadJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class DownloadCoordinatorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class FakeStore : DownloadJobStore {
        val jobs = HashMap<String, DownloadJob>()
        var requeued = 0

        override suspend fun get(videoId: String): DownloadJob? = jobs[videoId]

        override suspend fun save(job: DownloadJob) {
            jobs.putIfAbsent(job.videoId, job)
        }

        override suspend fun bindDestination(videoId: String, destinationDir: String) {
            val job = jobs[videoId] ?: return
            if (job.destinationDir == null) jobs[videoId] = job.copy(destinationDir = destinationDir)
        }

        override suspend fun claim(job: DownloadJob, now: Long): DownloadJob? {
            save(job)
            val previous = jobs.getValue(job.videoId)
            if (previous.status !in listOf("QUEUED", "WAITING_FOR_SPACE", "FAILED")) return null
            return previous.copy(status = "DOWNLOADING", attempts = previous.attempts + 1, updatedAt = now).also {
                jobs[job.videoId] = it
            }
        }

        override suspend fun finish(videoId: String, status: String, attempts: Int, updatedAt: Long, lastError: String?): Int {
            val job = jobs[videoId] ?: return 0
            if (job.status != "DOWNLOADING" || job.attempts != attempts) return 0
            setStatus(videoId, status, attempts, updatedAt, lastError)
            return 1
        }

        override suspend fun setStatus(
            videoId: String,
            status: String,
            attempts: Int,
            updatedAt: Long,
            lastError: String?
        ) {
            jobs[videoId] = (jobs[videoId] ?: return).copy(
                status = status,
                attempts = attempts,
                updatedAt = updatedAt,
                lastError = lastError
            )
        }

        override suspend fun resumable(): List<DownloadJob> =
            jobs.values.filter {
                it.status == DownloadJob.Status.QUEUED || it.status == DownloadJob.Status.WAITING_FOR_SPACE
            }

        override suspend fun completed(): List<DownloadJob> =
            jobs.values.filter { it.status == DownloadJob.Status.COMPLETED }

        override suspend fun requeueRunning(updatedAt: Long): Int {
            var count = 0
            jobs.replaceAll { _, job ->
                if (job.status == DownloadJob.Status.DOWNLOADING) {
                    count++
                    job.copy(status = DownloadJob.Status.QUEUED, updatedAt = updatedAt)
                } else {
                    job
                }
            }
            requeued = count
            return count
        }

        override suspend fun delete(videoId: String) {
            jobs.remove(videoId)
        }
    }

    private class FakeEngine(
        private val bytes: ByteArray = ByteArray(32),
        private val failure: Exception? = null
    ) : DownloadEngine {
        var calls = 0
        var lastDir: File? = null

        override suspend fun download(
            request: DownloadRequest,
            targetDir: File,
            onProgress: (Int) -> Unit
        ): File {
            calls++
            lastDir = targetDir
            onProgress(50)
            failure?.let { throw it }
            val temp = File(targetDir, "${request.videoId}.m4a.part")
            temp.writeBytes(bytes)
            onProgress(100)
            return temp
        }
    }

    private val request = DownloadRequest("vid", "Title", "Artist", null, 200_000L)

    private fun coordinator(
        store: DownloadJobStore,
        engine: DownloadEngine,
        free: Long = 500L * 1024 * 1024
    ) = DownloadCoordinator(
        store = store,
        engine = engine,
        targetDir = { temp.root },
        availableBytes = { free },
        clock = { 42L }
    )

    @Test
    fun acceptedDestinationSurvivesSettingsChange() = runBlocking {
        val first = temp.newFolder("internal")
        val second = temp.newFolder("external")
        var selected = first
        val store = FakeStore()
        val engine = FakeEngine()
        val runner = DownloadCoordinator(store, engine, { selected }, { Long.MAX_VALUE },
            authorizedRoots = { listOf(first, second) })
        runner.enqueue(request)
        selected = second
        runner.enqueue(request.copy(title = "Must not replace"))
        assertEquals("Title", store.get("vid")!!.title)
        assertTrue(runner.run(request) is DownloadResult.Published)
        assertTrue(File(first, "vid.m4a").isFile)
        assertFalse(File(second, "vid.m4a").exists())
    }

    @Test
    fun queuedRequestPersistsWhileAnotherWriterHoldsLock() = runBlocking {
        val store = FakeStore()
        val engine = object : DownloadEngine {
            override suspend fun download(request: DownloadRequest, targetDir: File, onProgress: (Int) -> Unit): File {
                val next = request.copy(videoId = "next")
                assertTrue(coordinator(store, FakeEngine()).run(next) is DownloadResult.Busy)
                assertEquals("QUEUED", store.get("next")!!.status)
                assertEquals(temp.root.canonicalPath, store.get("next")!!.destinationDir)
                return File(targetDir, "vid.m4a.part").apply { writeText("audio") }
            }
        }
        assertTrue(coordinator(store, engine).run(request) is DownloadResult.Published)
    }

    @Test
    fun onlyStoppedStagingIsReclaimed() = runBlocking {
        val safe = temp.newFolder(".download-safe-1")
        File(safe, "safe.m4a.part").writeText("partial")
        DownloadCoordinator.markStopped(safe)
        val live = temp.newFolder(".download-live-2")
        File(live, "live.m4a.part").writeText("live writer")
        val audio = File(temp.root, "keep.m4a").apply { writeText("completed") }
        coordinator(FakeStore(), FakeEngine()).recoverInterrupted()
        assertFalse(safe.exists())
        assertTrue(live.exists())
        assertEquals("completed", audio.readText())
    }

    @Test
    fun legacyJobProbesOtherRootAndDefersWhenUnavailable() = runBlocking {
        val first = temp.newFolder("old")
        val second = temp.newFolder("new")
        val store = FakeStore()
        store.save(DownloadJob("vid", "Title", "Artist", null, 0, "QUEUED", 0, 1, 1))
        val runner = DownloadCoordinator(store, FakeEngine(), { second }, { Long.MAX_VALUE },
            authorizedRoots = { listOf(first, second) }, rootsAvailable = { false })
        assertTrue(runner.run(request) is DownloadResult.WaitingForSpace)
        assertEquals(null, store.get("vid")!!.destinationDir)
        File(first, "vid.m4a").writeText("previously published")
        assertTrue(runner.run(request) is DownloadResult.AlreadyPresent)
        assertEquals(first.canonicalPath, store.get("vid")!!.destinationDir)
    }

    @Test
    fun hundredMegabytesPausesWithoutStartingEngine() = runBlocking {
        val engine = FakeEngine()
        val result = coordinator(FakeStore(), engine, 100L * 1024 * 1024).run(request)
        assertTrue(result is DownloadResult.WaitingForSpace)
        assertEquals(0, engine.calls)
    }

    @Test
    fun errorsNeverExposeEngineDetailsAndPreservePartial() = runBlocking {
        val partial = File(temp.root, "vid.m4a.part").apply { writeText("partial") }
        val result = coordinator(FakeStore(), FakeEngine(failure = IOException("secret-cookie /private/path"))).run(request)
        assertTrue(result is DownloadResult.Failed)
        assertFalse((result as DownloadResult.Failed).message.contains("secret-cookie"))
        assertTrue(partial.exists())
    }

    @Test
    fun recoveryDoesNotStealLiveDownload() = runBlocking {
        val store = FakeStore()
        val engine = object : DownloadEngine {
            override suspend fun download(request: DownloadRequest, targetDir: File, onProgress: (Int) -> Unit): File {
                assertEquals(0, coordinator(store, FakeEngine()).recoverInterrupted())
                return File(targetDir, "vid.m4a.part").apply { writeBytes(ByteArray(32)) }
            }
        }
        assertTrue(coordinator(store, engine).run(request) is DownloadResult.Published)
    }

    @Test
    fun rejectsEscapingVideoIdWithoutWritingOutsideRoot() = runBlocking {
        val engine = FakeEngine()
        assertTrue(coordinator(FakeStore(), engine).run(request.copy(videoId = "../escape")) is DownloadResult.Failed)
        assertEquals(0, engine.calls)
    }

    @Test
    fun simultaneousRequestCannotOverwriteLiveClaim() = runBlocking {
        val store = FakeStore()
        val engine = object : DownloadEngine {
            override suspend fun download(request: DownloadRequest, targetDir: File, onProgress: (Int) -> Unit): File {
                val attempt = store.get("vid")!!.attempts
                assertTrue(coordinator(store, FakeEngine()).run(request) is DownloadResult.Busy)
                assertEquals(attempt, store.get("vid")!!.attempts)
                assertEquals("DOWNLOADING", store.get("vid")!!.status)
                return File(targetDir, "vid.m4a.part").apply { writeBytes(ByteArray(32)) }
            }
        }
        assertTrue(coordinator(store, engine).run(request) is DownloadResult.Published)
    }

    @Test
    fun emptyOutputIsNeverPublished() = runBlocking {
        val result = coordinator(FakeStore(), FakeEngine(bytes = byteArrayOf())).run(request)
        assertTrue(result is DownloadResult.Failed)
        assertFalse(File(temp.root, "vid.m4a").exists())
    }

    @Test
    fun untrustedEnginePathIsNotPublishedOrDeleted() = runBlocking {
        val outside = temp.newFile("outside.m4a").apply { writeText("keep") }
        val root = temp.newFolder("audio")
        val engine = object : DownloadEngine {
            override suspend fun download(request: DownloadRequest, targetDir: File, onProgress: (Int) -> Unit) = outside
        }
        val result = DownloadCoordinator(FakeStore(), engine, { root }, { Long.MAX_VALUE }).run(request)
        assertTrue(result is DownloadResult.Failed)
        assertEquals("keep", outside.readText())
        assertFalse(File(root, "vid.m4a").exists())
    }

    @Test
    fun retryNeverReusesAnInterruptedOwnersWorkingDirectory() = runBlocking {
        val store = FakeStore()
        val first = FakeEngine(failure = IOException("interrupted"))
        coordinator(store, first).run(request)
        val leftover = File(first.lastDir!!, "vid.m4a.part.part").apply { writeText("owned by old process") }
        val second = FakeEngine()
        assertTrue(coordinator(store, second).run(request) is DownloadResult.Published)
        assertFalse(first.lastDir == second.lastDir)
        assertEquals("owned by old process", leftover.readText())
    }

    @Test
    fun unknownStorageFailsClosed() = runBlocking {
        assertTrue(coordinator(FakeStore(), FakeEngine(), -1).run(request) is DownloadResult.WaitingForSpace)
    }

    @Test
    fun publishesTempFileAndMarksJobCompleted() = runBlocking {
        val store = FakeStore()
        val engine = FakeEngine()
        val result = coordinator(store, engine).run(request)

        assertTrue(result is DownloadResult.Published)
        val file = (result as DownloadResult.Published).file
        assertTrue(file.isFile)
        assertEquals(32L, file.length())
        assertFalse(engine.lastDir!!.exists())
        assertEquals(DownloadJob.Status.COMPLETED, store.jobs.getValue("vid").status)
        assertFalse(File(temp.root, "vid.m4a.part").exists())
    }

    @Test
    fun existingCompletedAudioIsNeverReplacedOrRedownloaded() = runBlocking {
        val existing = File(temp.root, "vid.m4a").apply { writeBytes(ByteArray(16)) }
        val store = FakeStore()
        val engine = FakeEngine()
        val result = coordinator(store, engine).run(request)

        assertTrue(result is DownloadResult.AlreadyPresent)
        assertEquals(0, engine.calls)
        assertEquals(16L, existing.length())
        assertEquals(DownloadJob.Status.COMPLETED, store.jobs.getValue("vid").status)
    }

    @Test
    fun lowStorageBlocksBeforeDownloading() = runBlocking {
        val store = FakeStore()
        val engine = FakeEngine()
        val result = coordinator(store, engine, free = 1024L).run(request)

        assertTrue(result is DownloadResult.WaitingForSpace)
        assertEquals(0, engine.calls)
        assertEquals(DownloadJob.Status.WAITING_FOR_SPACE, store.jobs.getValue("vid").status)
    }

    @Test
    fun outOfSpaceFailureBecomesWaitingForSpace() = runBlocking {
        val store = FakeStore()
        val engine = FakeEngine(failure = IOException("No space left on device"))
        val result = coordinator(store, engine).run(request)

        assertTrue(result is DownloadResult.WaitingForSpace)
        assertEquals(DownloadJob.Status.WAITING_FOR_SPACE, store.jobs.getValue("vid").status)
        assertFalse(File(temp.root, "vid.m4a.part").exists())
    }

    @Test
    fun ordinaryFailureMarksFailedAndPreservesTemp() = runBlocking {
        val store = FakeStore()
        val engine = FakeEngine(failure = IOException("HTTP 403"))
        File(temp.root, "vid.m4a.part").writeText("partial")
        val result = coordinator(store, engine).run(request)

        assertTrue(result is DownloadResult.Failed)
        assertEquals(DownloadJob.Status.FAILED, store.jobs.getValue("vid").status)
        assertTrue(File(temp.root, "vid.m4a.part").exists())
    }

    @Test
    fun cancellationRequeuesTheJobAndPropagates() = runBlocking {
        val store = FakeStore()
        val engine = FakeEngine(failure = CancellationException("scope cancelled"))
        try {
            coordinator(store, engine).run(request)
            throw AssertionError("expected cancellation")
        } catch (e: CancellationException) {
            assertEquals(DownloadJob.Status.QUEUED, store.jobs.getValue("vid").status)
        }
    }

    @Test
    fun interruptedRunningJobsAreRequeued() = runBlocking {
        val store = FakeStore()
        store.save(
            DownloadJob(
                "vid", "Title", "Artist", null, 0L,
                DownloadJob.Status.DOWNLOADING, 1, 1L, 1L
            )
        )
        val coordinator = coordinator(store, FakeEngine())

        assertEquals(1, coordinator.recoverInterrupted())
        assertEquals(DownloadJob.Status.QUEUED, store.jobs.getValue("vid").status)
        assertEquals(0, coordinator.recoverInterrupted())
    }

    @Test
    fun retryNeverResumesPartialBytesFromAKilledAttempt() = runBlocking {
        val store = FakeStore()
        val staging = ArrayList<File>()
        var calls = 0
        val engine = object : DownloadEngine {
            override suspend fun download(request: DownloadRequest, targetDir: File, onProgress: (Int) -> Unit): File {
                calls++
                staging += targetDir
                val partial = File(targetDir, "${request.videoId}.m4a.part")
                if (calls == 1) {
                    // A killed writer abandons its partial bytes here, unmarked and unreclaimable.
                    partial.writeBytes(ByteArray(8) { 1 })
                    throw IOException("writer lost")
                }
                assertFalse("retry resumed the previous partial", partial.exists())
                partial.writeBytes(ByteArray(16) { 2 })
                return partial
            }
        }

        assertTrue(coordinator(store, engine).run(request) is DownloadResult.Failed)
        assertTrue(coordinator(store, engine).run(request) is DownloadResult.Published)
        assertEquals(2, staging.size)
        assertNotEquals(staging[0], staging[1])
        assertEquals(8L, File(staging[0], "vid.m4a.part").length())
        assertEquals(16L, File(temp.root, "vid.m4a").length())
    }
}
