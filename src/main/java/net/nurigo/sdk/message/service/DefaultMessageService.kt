package net.nurigo.sdk.message.service

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.datetime.Instant
import kotlinx.datetime.toKotlinInstant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import net.nurigo.sdk.message.exception.*
import net.nurigo.sdk.message.extension.toStringValueMap
import net.nurigo.sdk.message.lib.Authenticator
import net.nurigo.sdk.message.model.Balance
import net.nurigo.sdk.message.model.Count
import net.nurigo.sdk.message.model.Message
import net.nurigo.sdk.message.model.StorageType
import net.nurigo.sdk.message.request.*
import net.nurigo.sdk.message.response.*
import net.nurigo.sdk.message.response.ErrorResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.codec.binary.Base64
import retrofit2.Retrofit
import java.io.File
import java.io.FileInputStream

@OptIn(ExperimentalSerializationApi::class)
class DefaultMessageService(apiKey: String, apiSecretKey: String, domain: String) : MessageService {
    private var messageHttpService: MessageHttpService

    init {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val authInfo = Authenticator(apiKey, apiSecretKey).generateAuthInfo()
                val request: Request = chain.request()
                    .newBuilder()
                    .addHeader("Authorization", authInfo)
                    .build()
                chain.proceed(request)
            }
            .build()
        val contentType = "application/json".toMediaType()
        val jsonConfig = Json {
            coerceInputValues = true
            explicitNulls = false
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        messageHttpService = Retrofit.Builder()
            .baseUrl(domain)
            .addConverterFactory(jsonConfig.asConverterFactory(contentType))
            .client(client)
            .build()
            .create(MessageHttpService::class.java)
    }

    /**
     * 파일 업로드 API
     * @suppress 파일을 접근 가능한 경로로 입력하셔야 합니다.
     * */
    @Throws
    fun uploadFile(file: File, fileType: StorageType = StorageType.MMS, link: String? = null): String? {
        val length = file.length()
        val imageByte = ByteArray(length.toInt())
        val fis: FileInputStream?
        try {
            fis = FileInputStream(file)
            fis.read(imageByte)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val encodedFile = String(Base64.encodeBase64(imageByte))
        val fileRequest = FileUploadRequest(
            file = encodedFile,
            type = fileType,
            link = link
        )
        val response = this.messageHttpService.uploadFile(fileRequest).execute()
        if (response.isSuccessful) {
            return response.body()?.fileId
        } else {
            val errorResponse: ErrorResponse = Json.decodeFromString(response.errorBody()?.string() ?: "")
            throw NurigoFileUploadException(errorResponse.errorMessage)
        }
    }

    /**
     * 메시지 조회 API
     * */
    @Throws
    fun getMessageList(parameter: MessageListRequest?): MessageListResponse? {
        val generatedParameter = parameter ?: MessageListRequest()
        val mappedParameter = generatedParameter.toStringValueMap()
        val response = this.messageHttpService.getMessageList(mappedParameter).execute()

        if (response.isSuccessful) {
            return response.body()
        } else {
            val errorResponse: ErrorResponse = Json.decodeFromString(response.errorBody()?.string() ?: "")
            throw NurigoUnknownException("${errorResponse.errorCode}: ${errorResponse.errorMessage}")
        }
    }

    /**
     * 단일 메시지 발송 API
     * */
    @Throws
    fun sendOne(parameter: SingleMessageSendingRequest): SingleMessageSentResponse? {
        val response = this.messageHttpService.sendOne(parameter).execute()

        if (response.isSuccessful) {
            return response.body()
        } else {
            val errorResponse: ErrorResponse = Json.decodeFromString(response.errorBody()?.string() ?: "")
            when (errorResponse.errorCode) {
                "ValidationError" -> throw NurigoBadRequestException(errorResponse.errorMessage)
                "InvalidApiKey" -> throw NurigoInvalidApiKeyException(errorResponse.errorMessage)
                "FailedToAddMessage" -> throw NurigoBadRequestException(errorResponse.errorMessage)
                else -> throw NurigoUnknownException("${errorResponse.errorCode}: ${errorResponse.errorMessage}")
            }
        }
    }

    /**
     * 단일 메시지 발송 API
     * sendOne 및 sendMany 보다 더 개선된 오류 및 데이터 정보를 반환합니다.
     */
    @Throws(NurigoMessageNotReceivedException::class, NurigoEmptyResponseException::class, NurigoUnknownException::class)
    fun send(message: Message): MultipleDetailMessageSentResponse {
        val multipleParameter = MultipleDetailMessageSendingRequest(
            messages = listOf(message),
            scheduledDate = null
        )

        val response = this.messageHttpService.sendManyDetail(multipleParameter).execute()

        if (response.isSuccessful) {
            val responseBody = response.body()
            if (responseBody != null) {
                val count: Count = responseBody.groupInfo?.count ?: Count()
                val failedMessageList = responseBody.failedMessageList

                if (failedMessageList.isNotEmpty() && count.total == failedMessageList.count()) {
                    // TODO: i18n needed
                    val messageNotReceivedException = NurigoMessageNotReceivedException("메시지 발송 접수에 실패했습니다.")
                    messageNotReceivedException.failedMessageList = failedMessageList
                    throw messageNotReceivedException
                }

                return responseBody
            }
            throw NurigoEmptyResponseException("서버로부터 아무 응답을 받지 못했습니다.")
        } else {
            val errorString = response.errorBody()?.string() ?: "Server error encountered";
            throw NurigoUnknownException(errorString)
        }
    }

    /**
     * 단일 메시지 발송 및 다중 메시지(2건 이상) 예약 발송 API
     * sendOne 및 sendMany 보다 더 개선된 오류 및 데이터 정보를 반환합니다.
     */
    @Throws(NurigoMessageNotReceivedException::class, NurigoEmptyResponseException::class, NurigoUnknownException::class)
    fun send(message: Message, scheduledDateTime: java.time.Instant): MultipleDetailMessageSentResponse {
        val multipleParameter = MultipleDetailMessageSendingRequest(
            messages = listOf(message),
            scheduledDate = scheduledDateTime.toKotlinInstant()
        )
        val response = this.messageHttpService.sendManyDetail(multipleParameter).execute()

        if (response.isSuccessful) {
            val responseBody = response.body()
            if (responseBody != null) {
                val count: Count = responseBody.groupInfo?.count ?: Count()
                val failedMessageList = responseBody.failedMessageList

                if (failedMessageList.isNotEmpty() && count.total == failedMessageList.count()) {
                    // TODO: i18n needed
                    val messageNotReceivedException = NurigoMessageNotReceivedException("메시지 발송 접수에 실패했습니다.")
                    messageNotReceivedException.failedMessageList = failedMessageList
                    throw messageNotReceivedException
                }

                return responseBody
            }
            throw NurigoEmptyResponseException("서버로부터 아무 응답을 받지 못했습니다.")
        } else {
            val errorString = response.errorBody()?.string() ?: "Server error encountered";
            throw NurigoUnknownException(errorString)
        }
    }

    /**
     * 단일 메시지 발송 및 다중 메시지(2건 이상) 예약 발송 API
     * sendOne 및 sendMany 보다 더 개선된 오류 및 데이터 정보를 반환합니다.
     */
    @Throws(NurigoMessageNotReceivedException::class, NurigoEmptyResponseException::class, NurigoUnknownException::class)
    fun send(message: Message, scheduledDate: Instant): MultipleDetailMessageSentResponse {
        val multipleParameter = MultipleDetailMessageSendingRequest(
            messages = listOf(message),
            scheduledDate = scheduledDate
        )

        val response = this.messageHttpService.sendManyDetail(multipleParameter).execute()

        if (response.isSuccessful) {
            val responseBody = response.body()
            if (responseBody != null) {
                val count: Count = responseBody.groupInfo?.count ?: Count()
                val failedMessageList = responseBody.failedMessageList

                if (failedMessageList.isNotEmpty() && count.total == failedMessageList.count()) {
                    // TODO: i18n needed
                    val messageNotReceivedException = NurigoMessageNotReceivedException("메시지 발송 접수에 실패했습니다.")
                    messageNotReceivedException.failedMessageList = failedMessageList
                    throw messageNotReceivedException
                }

                return responseBody
            }
            throw NurigoEmptyResponseException("서버로부터 아무 응답을 받지 못했습니다.")
        } else {
            val errorString = response.errorBody()?.string() ?: "Server error encountered";
            throw NurigoUnknownException(errorString)
        }
    }

    /**
     * 다중 메시지(2건 이상) 발송 API
     * sendOne 및 sendMany 보다 더 개선된 오류 및 데이터 정보를 반환합니다.
     */
    @Throws(NurigoMessageNotReceivedException::class, NurigoEmptyResponseException::class, NurigoUnknownException::class)
    fun send(messages: List<Message>): MultipleDetailMessageSentResponse {
        val parameter = MultipleDetailMessageSendingRequest(
            messages,
            null
        )
        val response = this.messageHttpService.sendManyDetail(parameter).execute()

        if (response.isSuccessful) {
            val responseBody = response.body()
            if (responseBody != null) {
                val count: Count = responseBody.groupInfo?.count ?: Count()
                val failedMessageList = responseBody.failedMessageList

                if (failedMessageList.isNotEmpty() && count.total == failedMessageList.count()) {
                    // TODO: i18n needed
                    val messageNotReceivedException = NurigoMessageNotReceivedException("메시지 발송 접수에 실패했습니다.")
                    messageNotReceivedException.failedMessageList = failedMessageList
                    throw messageNotReceivedException
                }

                return responseBody
            }
            throw NurigoEmptyResponseException("서버로부터 아무 응답을 받지 못했습니다.")
        } else {
            val errorString = response.errorBody()?.string() ?: "Server error encountered";
            throw NurigoUnknownException(errorString)
        }
    }

    /**
     * 다중 메시지(2건 이상) 발송 및 예약 발송 API
     * sendOne 및 sendMany 보다 더 개선된 오류 및 데이터 정보를 반환합니다.
     */
    @Throws(NurigoMessageNotReceivedException::class, NurigoEmptyResponseException::class, NurigoUnknownException::class)
    fun send(messages: List<Message>, scheduledDateTime: java.time.Instant): MultipleDetailMessageSentResponse {
        val parameter = MultipleDetailMessageSendingRequest(
            messages,
            scheduledDateTime.toKotlinInstant()
        )
        val response = this.messageHttpService.sendManyDetail(parameter).execute()

        if (response.isSuccessful) {
            val responseBody = response.body()
            if (responseBody != null) {
                val count: Count = responseBody.groupInfo?.count ?: Count()
                val failedMessageList = responseBody.failedMessageList

                if (failedMessageList.isNotEmpty() && count.total == failedMessageList.count()) {
                    // TODO: i18n needed
                    val messageNotReceivedException = NurigoMessageNotReceivedException("메시지 발송 접수에 실패했습니다.")
                    messageNotReceivedException.failedMessageList = failedMessageList
                    throw messageNotReceivedException
                }

                return responseBody
            }
            throw NurigoEmptyResponseException("서버로부터 아무 응답을 받지 못했습니다.")
        } else {
            val errorString = response.errorBody()?.string() ?: "Server error encountered";
            throw NurigoUnknownException(errorString)
        }
    }

    /**
     * 다중 메시지(2건 이상) 발송 및 예약 발송 API
     * sendOne 및 sendMany 보다 더 개선된 오류 및 데이터 정보를 반환합니다.
     */
    @Throws(NurigoMessageNotReceivedException::class, NurigoEmptyResponseException::class, NurigoUnknownException::class)
    fun send(messages: List<Message>, scheduledDateTime: Instant): MultipleDetailMessageSentResponse {
        val parameter = MultipleDetailMessageSendingRequest(
            messages,
            scheduledDateTime
        )
        val response = this.messageHttpService.sendManyDetail(parameter).execute()

        if (response.isSuccessful) {
            val responseBody = response.body()
            if (responseBody != null) {
                val count: Count = responseBody.groupInfo?.count ?: Count()
                val failedMessageList = responseBody.failedMessageList

                if (failedMessageList.isNotEmpty() && count.total == failedMessageList.count()) {
                    // TODO: i18n needed
                    val messageNotReceivedException = NurigoMessageNotReceivedException("메시지 발송 접수에 실패했습니다.")
                    messageNotReceivedException.failedMessageList = failedMessageList
                    throw messageNotReceivedException
                }

                return responseBody
            }
            throw NurigoEmptyResponseException("서버로부터 아무 응답을 받지 못했습니다.")
        } else {
            val errorString = response.errorBody()?.string() ?: "Server error encountered";
            throw NurigoUnknownException(errorString)
        }
    }

    /**
     * 다중 메시지(2건 이상) 발송 API
     * @deprecated use Send method
     * */
    @Throws
    fun sendMany(parameter: MultipleMessageSendingRequest): MultipleMessageSentResponse? {
        val response = this.messageHttpService.sendMany(parameter).execute()

        if (response.isSuccessful) {
            return response.body()
        } else {
            val errorResponse: ErrorResponse = Json.decodeFromString(response.errorBody()?.string() ?: "")
            when (errorResponse.errorCode) {
                "ValidationError" -> throw NurigoBadRequestException(errorResponse.errorMessage)
                "InvalidApiKey" -> throw NurigoInvalidApiKeyException(errorResponse.errorMessage)
                "FailedToAddMessage" -> throw NurigoBadRequestException(errorResponse.errorMessage)
                else -> throw NurigoUnknownException("${errorResponse.errorCode}: ${errorResponse.errorMessage}")
            }
        }
    }

    /**
     * 잔액 조회 API
     */
    @Throws
    fun getBalance(): Balance {
        val response = this.messageHttpService.getBalance().execute()
        if (response.isSuccessful) {
            return response.body() ?: throw NurigoUnknownException("잔액 조회 데이터를 불러오지 못했습니다.")
        } else {
            val errorResponse: ErrorResponse = Json.decodeFromString(response.errorBody()?.string() ?: "")
            throw NurigoUnknownException("${errorResponse.errorCode}: ${errorResponse.errorMessage}")
        }
    }
}