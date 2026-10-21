package cn.yukehelper.core

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import kotlinx.serialization.json.*

/**
 * Dynamic attendance QR: the code content is a signed yuketang URL.
 *
 * The raw string is only ever forwarded (as JSON) to the yuketang scan endpoint; this client never
 * fetches it, so the check below is about recognising the right kind of code and refusing junk or
 * phishing content, not about SSRF protection.
 */
object QrContent {
    const val MAX_BYTES = 2_048

    sealed interface Check {
        data class Yuketang(val raw: String) : Check
        data object Empty : Check
        data object TooLong : Check
        data object NotHttps : Check
        data object NotYuketang : Check
    }

    fun check(raw: String): Check {
        val text = raw.trim()
        if (text.isEmpty()) return Check.Empty
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return Check.TooLong
        if (text.any { it.isISOControl() }) return Check.NotYuketang
        val uri = try { URI(text) } catch (_: URISyntaxException) { return Check.NotYuketang }
        if (!uri.scheme.equals("https", ignoreCase = true)) return Check.NotHttps
        // A hostless or userinfo-bearing URL is never a classroom code.
        if (!uri.userInfo.isNullOrBlank() || uri.host.isNullOrBlank()) return Check.NotYuketang
        if (uri.port != -1 && uri.port != 443) return Check.NotYuketang
        val host = uri.host.lowercase(Locale.ROOT)
        if (host != "yuketang.cn" && !host.endsWith(".yuketang.cn")) return Check.NotYuketang
        return Check.Yuketang(text)
    }
}

/** Why a dynamic QR check-in did not complete. Only fixed categories and numeric codes are kept. */
enum class AttendanceIssue(val summary: String, val advice: String) {
    EMPTY("没有读到二维码内容", "请对准课堂大屏上的雨课堂签到二维码"),
    TOO_LONG("二维码内容过长", "请扫描课堂大屏上的原始二维码，不要复制图片或网址全文"),
    NOT_HTTPS("二维码不是安全的雨课堂链接", "请扫描课堂大屏上的雨课堂签到二维码"),
    NOT_YUKETANG("这不是雨课堂二维码", "微信个人码、群二维码等无法用于签到"),
    LOGIN_CODE("这是登录二维码，不是签到码", "请扫描课堂大屏上几秒钟刷新一次的签到码"),
    MALFORMED("雨课堂未接受该二维码", "二维码可能已刷新，请重新扫描最新的一张"),
    NOT_FOUND("雨课堂未找到该二维码", "二维码可能已刷新或已结束，请重新扫描"),
    EXPIRED("动态二维码已过期", "动态码几秒钟刷新一次，请扫描屏幕上的最新码"),
    DENIED("当前账号无权签到该课堂", "请确认账号与站点，并在官方雨课堂核对入课权限"),
    FINISHED("课堂已结束，无法签到", "请在课堂进行期间签到"),
    RATE_LIMIT("签到请求过于频繁", "稍等片刻后再扫一次"),
    UNKNOWN("签到未完成", "请重新扫描；若仍失败，请在官方雨课堂核对考勤状态"),
}

/** The scan endpoint resolved a real check-in code to a lesson id. */
sealed interface ScanDispatch {
    data class CheckIn(val lessonId: JsonPrimitive) : ScanDispatch
    data class Other(val type: String) : ScanDispatch
}

data class SignInReceipt(val lessonId: String, val lessonToken: String, val lessonName: String = "")

sealed interface AttendanceOutcome {
    data class Signed(val lessonId: String, val lessonName: String, val repeated: Boolean = false) : AttendanceOutcome
    data class Rejected(val issue: AttendanceIssue, val code: Int? = null) : AttendanceOutcome
}

/** Only fixed categories and numeric status cross the network boundary; never an upstream payload. */
class AttendanceRejected(val issue: AttendanceIssue, val code: Int? = null) : Exception(
    issue.summary + code?.let { "（接口代码 $it）" }.orEmpty(),
)

interface AttendanceGateway {
    suspend fun dispatchScan(account: Account, raw: String): ScanDispatch
    suspend fun signIn(account: Account, lessonId: JsonPrimitive): SignInReceipt
}

/**
 * Two-step sign-in, matching the official client:
 *   1. POST /api/v3/app/scan {"url": <QR content>} -> data.value is the lesson id
 *   2. POST /api/v3/lesson/checkin {"source":21,"lessonId":...,"joinIfNotIn":true}
 *
 * Repeating a code that this process already signed is answered locally, so a re-scanned screenshot
 * cannot produce a second check-in request. A fresh process re-asks the server instead of guessing.
 */
class ScanAttendance(private val gateway: AttendanceGateway) {
    private val lock = Any()
    /** code content -> lesson id, so tapping the same frame twice never repeats a request. */
    private val acceptedByCode = LinkedHashMap<String, String>()
    /** account|lesson, so a refreshed code for an already signed lesson never posts twice. */
    private val signedLessons = LinkedHashSet<String>()

    suspend fun submit(account: Account, raw: String): AttendanceOutcome {
        val text = when (val check = QrContent.check(raw)) {
            is QrContent.Check.Yuketang -> check.raw
            QrContent.Check.Empty -> return AttendanceOutcome.Rejected(AttendanceIssue.EMPTY)
            QrContent.Check.TooLong -> return AttendanceOutcome.Rejected(AttendanceIssue.TOO_LONG)
            QrContent.Check.NotHttps -> return AttendanceOutcome.Rejected(AttendanceIssue.NOT_HTTPS)
            QrContent.Check.NotYuketang -> return AttendanceOutcome.Rejected(AttendanceIssue.NOT_YUKETANG)
        }
        val codeKey = "${account.key}|$text"
        synchronized(lock) {
            acceptedByCode[codeKey]?.let { return AttendanceOutcome.Signed(it, "", repeated = true) }
        }
        val dispatch = try {
            gateway.dispatchScan(account, text)
        } catch (error: AttendanceRejected) {
            return AttendanceOutcome.Rejected(error.issue, error.code)
        } catch (_: NetworkFailure) {
            return AttendanceOutcome.Rejected(AttendanceIssue.UNKNOWN)
        }
        val lessonId = when (dispatch) {
            is ScanDispatch.CheckIn -> dispatch.lessonId
            is ScanDispatch.Other -> return AttendanceOutcome.Rejected(AttendanceIssue.LOGIN_CODE)
        }
        val lessonKey = "${account.key}|${lessonId.content}"
        synchronized(lock) {
            if (lessonKey in signedLessons) {
                remember(codeKey, lessonId.content)
                return AttendanceOutcome.Signed(lessonId.content, "", repeated = true)
            }
        }
        val receipt = try {
            gateway.signIn(account, lessonId)
        } catch (error: AttendanceRejected) {
            return AttendanceOutcome.Rejected(error.issue, error.code)
        } catch (_: NetworkFailure) {
            return AttendanceOutcome.Rejected(AttendanceIssue.UNKNOWN)
        }
        synchronized(lock) {
            signedLessons.add(lessonKey)
            remember(codeKey, lessonId.content)
        }
        return AttendanceOutcome.Signed(lessonId.content, receipt.lessonName)
    }

    /** Bounded, so a long session of re-scans cannot grow without limit. */
    private fun remember(codeKey: String, lessonId: String) {
        acceptedByCode[codeKey] = lessonId
        while (acceptedByCode.size > 200) acceptedByCode.remove(acceptedByCode.keys.first())
        while (signedLessons.size > 200) signedLessons.remove(signedLessons.first())
    }
}
