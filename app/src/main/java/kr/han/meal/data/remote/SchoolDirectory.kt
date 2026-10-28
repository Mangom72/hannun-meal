package kr.han.meal.data.remote

import kr.han.meal.domain.model.School
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.time.*
import java.util.Locale

@Serializable private data class DirectoryIndex(val schemaVersion:Int,val generatedAt:String,val count:Int,val sha256:String,val file:String)
private val offices=setOf("B10","C10","D10","E10","F10","G10","H10","I10","J10","K10","M10","N10","P10","Q10","R10","S10","T10")
private val schoolTypes=setOf("초등학교","중학교","고등학교")
fun directoryHash(data:ByteArray)=MessageDigest.getInstance("SHA-256").digest(data).joinToString("") {"%02x".format(it)}
class SchoolDirectory(private val transport:PublicJson,private val directory:File,private val clock:Clock=Clock.systemUTC(),private val minimum:Int=10000) {
    private val json=Json {ignoreUnknownKeys=true}
    private val mutex=Mutex()
    private var source=""
    @Volatile private var schools:List<School> = emptyList()
    @Volatile private var searchable:List<Triple<School,String,String>> = emptyList()
    private fun install(rows:List<School>) {val index=rows.map {Triple(it,normalize(it.name),normalize("${it.name} ${it.region} ${it.address} ${it.type}"))};searchable=index;schools=rows}
    private var checkedAt:Instant?=null
    private var payloadHash:String?=null
    val ready get()=schools.isNotEmpty()
    private fun parse(text:String,count:Int?=null):List<School> {
        val root=json.parseToJsonElement(text).jsonObject
        require(root["schemaVersion"]?.jsonPrimitive?.int==1)
        val rows=root["schools"]!!.jsonArray.map {element->
            val r=element.jsonArray.map {it.jsonPrimitive.content}
            require(r.size==6 && r.all {it.isNotBlank()} && r[0] in offices && Regex("[A-Z0-9]{7}").matches(r[1]) && r[3] in schoolTypes)
            School(r[0],r[1],r[2],r[5],r[3],r[4])
        }
        require(rows.size>=minimum && (count==null || count==rows.size) && rows.map {it.id}.distinct().size==rows.size)
        return rows
    }
    suspend fun load(endpoint:String)=withContext(Dispatchers.IO) {mutex.withLock {
        val url=publicEndpoint(endpoint).toString()
        if(source==url && schools.isNotEmpty()) return@withLock
        source=url;install(emptyList());checkedAt=null;payloadHash=null
        val file=File(directory,directoryHash(url.toByteArray())+".json")
        if(file.isFile) runCatching {val text=file.readText();install(parse(text));payloadHash=directoryHash(text.toByteArray(Charsets.UTF_8));checkedAt=Instant.ofEpochMilli(file.lastModified())}
    }}
    suspend fun refresh(endpoint:String)=withContext(Dispatchers.IO) {
        load(endpoint)
        mutex.withLock {
            if(checkedAt?.plus(Duration.ofHours(24))?.isAfter(clock.instant())==true) return@withLock
            val base=publicEndpoint(endpoint)
            try {
                val index=json.decodeFromString<DirectoryIndex>(transport.get(base.newBuilder().addPathSegment("schools-index.json").build(),65536))
                require(index.schemaVersion==1 && index.count>=minimum && Regex("[a-f0-9]{64}").matches(index.sha256) && index.file=="schools.${index.sha256}.json")
                require(Instant.parse(index.generatedAt)<=clock.instant().plusSeconds(300))
                val stored=File(directory,directoryHash(base.toString().toByteArray())+".json")
                if(index.sha256==payloadHash && index.count==schools.size && stored.isFile) {
                    stored.setLastModified(clock.millis());checkedAt=clock.instant();return@withLock
                }
                val text=transport.get(base.newBuilder().addPathSegment(index.file).build(),8L*1024*1024)
                require(directoryHash(text.toByteArray(Charsets.UTF_8))==index.sha256)
                val fresh=parse(text,index.count)
                directory.mkdirs()
                val file=File(directory,directoryHash(base.toString().toByteArray())+".json"); val temp=File(directory,file.name+".tmp")
                temp.writeText(text);require(temp.renameTo(file));file.setLastModified(clock.millis())
                install(fresh);payloadHash=index.sha256;checkedAt=clock.instant()
            } catch(e:CancellationException) {throw e} catch(e:Exception) {
                // Failed refresh never removes the prior validated index, on disk or in memory.
                if(schools.isNotEmpty()) checkedAt=clock.instant().minus(Duration.ofHours(23))
                throw NeisException(if(e is NeisException) e.kind else ErrorKind.INVALID,"학교 목록을 갱신하지 못했습니다. 저장된 목록은 계속 사용할 수 있습니다")
            }
        }
    }
    fun search(query:String,type:String?=null):List<School> {
        val terms=normalize(query).split(' ').filter {it.isNotBlank()}
        if(terms.isEmpty()) return emptyList()
        return searchable.asSequence().filter {(s,_,text)->(type==null || s.type==type) && terms.all {text.contains(it)}}.sortedWith(compareBy<Triple<School,String,String>> {!it.second.startsWith(terms.first())}.thenBy {it.first.name}.thenBy {it.first.region}.thenBy {it.first.code}).take(100).map {it.first}.toList()
    }
    private fun normalize(s:String)=Normalizer.normalize(s,Normalizer.Form.NFC).lowercase(Locale.KOREAN).trim().replace(Regex("\\s+")," ")
}
