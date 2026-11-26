package app.echoread

import android.content.SharedPreferences
import app.echoread.core.i18n.S
import app.echoread.data.UpdateInfo
import app.echoread.data.UpdateState
import app.echoread.data.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 应用内更新：版本比较、「忽略这一版」的持久化、强制 SHA-256、镜像回退、并发下载守卫。
 * 清单与安装包都由本机的假服务端提供，走真实的 OkHttp 路径。
 */
class UpdaterTest {
    private val dir: File = Files.createTempDirectory("updater-test").toFile()
    private val server = MockWebServer().apply { start() }

    @After
    fun cleanUp() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private val lenient = Json { ignoreUnknownKeys = true }
    private val apk = ByteArray(64 * 1024) { (it % 251).toByte() }
    private val apkSha = sha256(apk)

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun info(versionCode: Long = 20, sha: String = apkSha, minSdk: Int = 26, versionName: String = "0.8.0", mirrors: List<String> = emptyList()) =
        UpdateInfo(versionCode, versionName, server.url("/a.apk").toString(), mirrors, sha256 = sha, minSdk = minSdk)

    private fun manifest(i: UpdateInfo) = MockResponse().setBody(Json.encodeToString(UpdateInfo.serializer(), i))

    private fun apkResponse(bytes: ByteArray = apk) = MockResponse().setBody(Buffer().write(bytes))

    private fun updater(prefs: SharedPreferences = MemoryPrefs(), installed: Long = 19, sdk: Int = 30) =
        Updater(prefs, dir, { installed to "0.7.0" }, sdk, listOf(server.url("/update.json").toString()))

    @Test
    fun versionComparison() = runBlocking {
        val u = updater()
        server.enqueue(manifest(info(versionCode = 20)))
        assertTrue(u.check(force = true) is UpdateState.Available)
        server.enqueue(manifest(info(versionCode = 19)))
        assertEquals("同一版本", UpdateState.UpToDate, u.check(force = true))
        server.enqueue(manifest(info(versionCode = 20, minSdk = 31)))
        assertEquals("系统版本不够", UpdateState.UpToDate, u.check(force = true))
    }

    /** 叉掉的版本：重启后（新实例、同一份存档）的静默检查不再提示，手动检查照常提示 */
    @Test
    fun dismissSurvivesRestart() = runBlocking {
        val prefs = MemoryPrefs()
        val before = updater(prefs)
        server.enqueue(manifest(info()))
        assertTrue(before.check(force = true) is UpdateState.Available)
        before.dismiss()
        assertEquals(UpdateState.Idle, before.state.value)

        val after = updater(prefs)
        assertTrue(after.isDismissed(20))
        prefs.edit().putLong("last-check", 0).apply() // 跨过 24 小时的静默间隔
        server.enqueue(manifest(info()))
        assertEquals(UpdateState.UpToDate, after.check(force = false))
        server.enqueue(manifest(info()))
        assertTrue(after.check(force = true) is UpdateState.Available)
    }

    /** 没拿到清单（离线、服务端出错）不记检查时间：下次启动的静默检查照常进行；拿到了才记 */
    @Test
    fun failedCheckDoesNotSuppressTheNextOne() = runBlocking {
        val u = updater()
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(u.check(force = false) is UpdateState.Error)
        server.enqueue(manifest(info(versionCode = 19)))
        assertEquals(UpdateState.UpToDate, u.check(force = false))
        assertEquals(2, server.requestCount)
        u.check(force = false)
        assertEquals("成功之后 24 小时内不再请求", 2, server.requestCount)
    }

    /** 清单里没有合法的 SHA-256：不下载、不认本地包；校验对不上的包不落地 */
    @Test
    fun checksumIsMandatory() = runBlocking {
        val u = updater()
        for (bad in listOf("", "abc", apkSha.dropLast(1) + "g")) {
            val s = u.download(info(sha = bad))
            assertEquals(UpdateState.Error(S.update.missingChecksum, info(sha = bad)), s)
        }
        assertEquals("一个字节都没下载", 0, server.requestCount)

        server.enqueue(apkResponse(apk.copyOf().also { it[0] = 7 }))
        assertTrue(u.download(info()) is UpdateState.Error)
        assertTrue("校验失败的包不落地", dir.walk().none { it.isFile })

        // 本地已有的同版本包：哈希对得上才算就绪；清单缺校验值时一律不认
        val cached = File(dir, "updates/Lector-v0.8.0.apk").apply { parentFile!!.mkdirs(); writeBytes(apk) }
        server.enqueue(manifest(info()))
        assertEquals(UpdateState.Ready(info(), cached), u.check(force = true))
        server.enqueue(manifest(info(sha = "")))
        assertTrue(updater().check(force = true) is UpdateState.Available)

        // 已发布的清单必须过得了这道关，否则所有旧版本都会卡在更新上
        val shipped = lenient.decodeFromString(UpdateInfo.serializer(), File("../update.json").readText())
        assertTrue(shipped.sha256, Updater.validSha256(shipped.sha256))
        assertTrue(Updater.validSha256(apkSha.uppercase()))
    }

    /** 主地址失败（404 或包被换过）就换镜像，下回来的包仍要对上清单里的哈希 */
    @Test
    fun mirrorFallback() = runBlocking {
        val u = updater()
        val mirror = server.url("/mirror.apk").toString()
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(apkResponse())
        val ok = u.download(info(mirrors = listOf(mirror)))
        assertTrue(ok.toString(), ok is UpdateState.Ready)
        assertArrayEquals(apk, (ok as UpdateState.Ready).file.readBytes())
        assertEquals(listOf("/a.apk", "/mirror.apk"), List(2) { server.takeRequest().path })

        server.enqueue(apkResponse(apk.copyOf().also { it[1] = 9 }))
        server.enqueue(apkResponse())
        assertTrue(u.download(info(mirrors = listOf(mirror))) is UpdateState.Ready)
    }

    /** 版本名来自网上的清单：「../」之类不能把安装包写到缓存目录之外 */
    @Test
    fun versionNameCannotEscapeTheCacheDir() = runBlocking {
        server.enqueue(apkResponse())
        val s = updater().download(info(versionName = "../../evil/../x"))
        val f = (s as UpdateState.Ready).file
        assertEquals(File(dir, "updates").canonicalFile, f.canonicalFile.parentFile)
        assertFalse(f.name.contains('/'))
    }

    /** 下载进行中再点一次：不开第二个下载（同一个 .part 文件会被交错写坏），直接拿到进行中的状态 */
    @Test
    fun concurrentDownloadIsGuarded() = runBlocking {
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                release.await(10, TimeUnit.SECONDS)
                return apkResponse()
            }
        }
        val u = updater()
        val first = async(Dispatchers.IO) { u.download(info()) }
        assertNotNull("第一个下载已经发出请求", server.takeRequest(10, TimeUnit.SECONDS))
        val second = u.download(info())
        assertTrue(second.toString(), second is UpdateState.Downloading)
        release.countDown()
        assertTrue(first.await() is UpdateState.Ready)
        assertEquals(1, server.requestCount)
    }
}

/** 内存版 SharedPreferences：同一个实例当作「重启前后的同一份存档」 */
private class MemoryPrefs : SharedPreferences {
    private val map = HashMap<String, Any?>()

    override fun getAll(): Map<String, *> = HashMap(map)
    override fun getString(key: String, defValue: String?): String? = map[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = map[key] as Set<String>? ?: defValues
    override fun getInt(key: String, defValue: Int): Int = map[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long): Long = map[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = map[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as Boolean? ?: defValue
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private fun put(key: String, value: Any?): SharedPreferences.Editor {
            map[key!!] = value
            return this
        }

        override fun putString(key: String, value: String?) = put(key, value)
        override fun putStringSet(key: String, values: Set<String>?) = put(key, values)
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)
        override fun remove(key: String): SharedPreferences.Editor {
            map.remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            map.clear()
            return this
        }

        override fun commit(): Boolean = true
        override fun apply() {}
    }
}
