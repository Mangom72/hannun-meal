package kr.han.meal.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable data class GitHubAsset(val id:Long,val name:String,val size:Long)
@Serializable data class GitHubRelease(val tag_name:String,val draft:Boolean=false,val prerelease:Boolean=false,val assets:List<GitHubAsset>)
class GitHubTransport(
    private val baseUrl:HttpUrl="https://api.github.com/".toHttpUrl(),
    client:OkHttpClient=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(120,TimeUnit.SECONDS).build(),
    private val allowRedirect:(HttpUrl)->Boolean={url->url.scheme=="https" && url.port==443 && url.host in setOf("release-assets.githubusercontent.com","objects.githubusercontent.com","github-releases.githubusercontent.com")}
) {
    private val client=client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val json=Json {ignoreUnknownKeys=true}
    private fun request(path:String,asset:Boolean=false)=Request.Builder().url(baseUrl.resolve(path) ?: throw UpdateException(UpdateError.CONFIG))
        .header("Accept",if(asset) "application/octet-stream" else "application/vnd.github+json")
        .header("X-GitHub-Api-Version","2022-11-28").header("User-Agent","HannunMeal-Android")
        .build()
    private suspend fun execute(request:Request):Response=suspendCancellableCoroutine {continuation->
        val call=client.newCall(request)
        continuation.invokeOnCancellation {call.cancel()}
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException) {if(continuation.isActive) continuation.resumeWithException(UpdateException(UpdateError.NETWORK))}
            override fun onResponse(call:Call,response:Response) {if(continuation.isActive) continuation.resume(response,onCancellation={_,value,_->value.close()}) else response.close()}
        })
    }
    private fun error(response:Response):UpdateException=UpdateException(when {
        response.code==401 -> UpdateError.AUTH
        response.code==429 || response.code==403 && response.header("X-RateLimit-Remaining")=="0" -> UpdateError.RATE_LIMIT
        response.code==403 -> UpdateError.AUTH
        response.code==404 -> UpdateError.NOT_FOUND
        response.code>=500 -> UpdateError.SERVER
        else -> UpdateError.INVALID
    })
    suspend fun latest(repository:String):GitHubRelease=withContext(Dispatchers.IO) {
        if(!UpdateRules.validRepository(repository)) throw UpdateException(UpdateError.CONFIG)
        execute(request("repos/$repository/releases/latest")).use {r->
            if(!r.isSuccessful) throw error(r)
            val bytes=readLimited(r,512*1024)
            val release=try {json.decodeFromString<GitHubRelease>(bytes.toString(Charsets.UTF_8))} catch(e:Exception) {throw UpdateException(UpdateError.INVALID)}
            if(release.draft || release.prerelease || release.assets.size>100 || release.tag_name.length>120) throw UpdateException(UpdateError.INVALID)
            release
        }
    }
    private suspend fun asset(repository:String,id:Long):Response {
        if(!UpdateRules.validRepository(repository) || id<=0) throw UpdateException(UpdateError.INVALID)
        var response=execute(request("repos/$repository/releases/assets/$id",true))
        repeat(3) {
            if(response.isSuccessful) return response
            if(response.code !in setOf(301,302,303,307,308)) {val failure=error(response);response.close();throw failure}
            val location=response.header("Location")?.let {response.request.url.resolve(it)}
            response.close()
            if(location==null || !allowRedirect(location)) throw UpdateException(UpdateError.INVALID)
            // Signed CDN URLs are short-lived. Never persist them or add authentication headers.
            response=execute(Request.Builder().url(location).header("Accept","application/octet-stream").build())
        }
        response.close();throw UpdateException(UpdateError.INVALID)
    }
    private suspend fun readLimited(response:Response,max:Int):ByteArray {
        val body=response.body ?: throw UpdateException(UpdateError.INVALID)
        if(body.contentLength()>max) throw UpdateException(UpdateError.INVALID)
        return body.byteStream().use {input->
            val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true) {coroutineContext.ensureActive();val count=input.read(buffer);if(count<0) break;if(output.size()+count>max) throw UpdateException(UpdateError.INVALID);output.write(buffer,0,count)}
            output.toByteArray()
        }
    }
    suspend fun manifest(repository:String,descriptor:GitHubAsset):UpdateManifest=withContext(Dispatchers.IO) {
        if(descriptor.size !in 1..65536) throw UpdateException(UpdateError.INVALID)
        asset(repository,descriptor.id).use {r->
            val bytes=readLimited(r,65536)
            if(bytes.size.toLong()!=descriptor.size) throw UpdateException(UpdateError.INVALID)
            try {json.decodeFromString<UpdateManifest>(bytes.toString(Charsets.UTF_8))} catch(e:Exception) {throw UpdateException(UpdateError.INVALID)}
        }
    }
    suspend fun download(repository:String,id:Long,file:File,size:Long,progress:(Int)->Unit)=withContext(Dispatchers.IO) {
        if(size !in 1..UpdateRules.MAX_APK_BYTES) throw UpdateException(UpdateError.INVALID)
        asset(repository,id).use {r->
            val body=r.body ?: throw UpdateException(UpdateError.INVALID)
            if(body.contentLength()>size) throw UpdateException(UpdateError.INVALID)
            body.byteStream().use {input->file.outputStream().use {output->
                var total=0L;var last=-1;val buffer=ByteArray(64*1024)
                while(true) {
                    coroutineContext.ensureActive();val count=input.read(buffer);if(count<0) break
                    total+=count;if(total>size) throw UpdateException(UpdateError.INVALID)
                    output.write(buffer,0,count)
                    val value=(total*100/size).toInt();if(value!=last) {progress(value);last=value}
                }
                if(total!=size) throw UpdateException(UpdateError.INVALID)
            }}
        }
    }
}
