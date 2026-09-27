package g.sw.erp.topology

import com.sun.net.httpserver.HttpExchange
import g.sw.db.Db
import g.sw.spi.ErpModule
import g.sw.spi.Json
import g.sw.spi.MountContext
import g.sw.spi.respond

/**
 * One node in the topology: the single Star (authoritative, lives on the Star
 * server itself) or a Planet (cloud relay). Persisted via [Db] on the Star only;
 * Planets keep only an in-memory cache.
 */
data class TopologyNode(
    val kind: String,
    val name: String,
    val httpHost: String,
    val httpPort: Int,
    val tunnelHost: String?,
    val tunnelPort: Int,
    val reachable: Boolean,
    val lastSeen: Long,
)

/**
 * Star side of discovery: the authoritative, DB-backed address book. Planet
 * entries are learnt from the Star's own configuration (`--planet=…`, or a
 * runtime registration) and are marked reachable by the relay supervisor as
 * their tunnels come and go. Served to satellites as `GET /api/topology` and
 * pulled by Planets over the relay tunnel for their in-memory caches.
 */
class TopologyModule(
    private val db: Db,
    private val starName: String,
    private val starHttpHost: String,
    private val starHttpPort: Int,
) : ErpModule {

    override val name: String = "topology"

    private val planets by lazy { db.collection(COLLECTION, TopologyNode::class.java) }

    override fun mount(context: MountContext) {
        context.handle("GET", "", ::serveTopology)
        context.handle("GET", "/", ::serveTopology)
        context.handle("POST", "/planets", ::registerPlanetHttp)
    }

    fun registerPlanet(httpHost: String, httpPort: Int, tunnelHost: String, tunnelPort: Int): TopologyNode {
        val node = TopologyNode(
            kind = "planet",
            name = "$httpHost:$httpPort",
            httpHost = httpHost,
            httpPort = httpPort,
            tunnelHost = tunnelHost,
            tunnelPort = tunnelPort,
            reachable = false,
            lastSeen = now(),
        )
        planets.put(node.name, node)
        return node
    }

    fun markReachable(name: String, reachable: Boolean) {
        val existing = planets.get(name) ?: return
        if (existing.reachable == reachable) return
        planets.put(name, existing.copy(reachable = reachable, lastSeen = now()))
    }

    fun listPlanets(): List<TopologyNode> =
        planets.entries().mapNotNull { it.second }.sortedBy { it.name }

    fun self(): TopologyNode = TopologyNode(
        kind = "star",
        name = starName,
        httpHost = starHttpHost,
        httpPort = starHttpPort,
        tunnelHost = null,
        tunnelPort = 0,
        reachable = true,
        lastSeen = 0L,
    )

    fun topologyMap(): LinkedHashMap<String, Any?> = linkedMapOf(
        "star" to nodeMap(self()),
        "planets" to listPlanets().map { nodeMap(it) },
    )

    private fun serveTopology(exchange: HttpExchange) {
        exchange.respond(200, Json.write(topologyMap()))
    }

    private fun registerPlanetHttp(exchange: HttpExchange) {
        val body = exchange.requestBody.bufferedReader().use { it.readText() }
        val json = runCatching { Json.parse(body) as? Map<*, *> }.getOrNull()
        if (json == null) {
            exchange.respond(400, """{"error":"expected a JSON body"}""")
            return
        }
        val httpHost = json["httpHost"] as? String
        val httpPort = (json["httpPort"] as? Number)?.toInt()
        val tunnelHost = (json["tunnelHost"] as? String) ?: httpHost
        val tunnelPort = (json["tunnelPort"] as? Number)?.toInt()
        if (httpHost == null || httpPort == null || tunnelHost == null || tunnelPort == null) {
            exchange.respond(400, """{"error":"need httpHost, httpPort, tunnelHost, tunnelPort"}""")
            return
        }
        val node = registerPlanet(httpHost, httpPort, tunnelHost, tunnelPort)
        exchange.respond(201, Json.write(mapOf("planet" to nodeMap(node))))
    }

    private fun nodeMap(node: TopologyNode): LinkedHashMap<String, Any?> = linkedMapOf(
        "kind" to node.kind,
        "name" to node.name,
        "httpHost" to node.httpHost,
        "httpPort" to node.httpPort,
        "tunnelHost" to node.tunnelHost,
        "tunnelPort" to node.tunnelPort,
        "reachable" to node.reachable,
        "lastSeen" to node.lastSeen,
    )

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        const val COLLECTION = "topology"
    }
}

/**
 * Stateless Planet-side cache: whatever topologies the Planet pulled from its
 * connected Stars, keyed by Star alias. Planets never persist this. [snapshot]
 * merges every star's address book into one topology (first star, planets
 * unioned by name, preferring reachable entries) for `GET /planet/topology`.
 */
class TopologyCache {

    private val lock = Any()
    private val byStar = LinkedHashMap<String, Map<String, Any?>>()

    val size: Int get() = synchronized(lock) { byStar.size }

    fun update(alias: String, topology: Map<String, Any?>) {
        synchronized(lock) { byStar[alias] = topology }
    }

    fun snapshot(): Map<String, Any?>? = synchronized(lock) {
        if (byStar.isEmpty()) return null
        val star = byStar.values.mapNotNull { it["star"] as? Map<*, *> }.firstOrNull() ?: return null
        val planets = LinkedHashMap<String, Map<*, *>>()
        for (topology in byStar.values) {
            val list = topology["planets"] as? List<*> ?: continue
            for (entry in list) {
                val node = entry as? Map<*, *> ?: continue
                val name = node["name"]?.toString() ?: continue
                val prev = planets[name]
                if (prev == null || (node["reachable"] != false && prev["reachable"] == false)) {
                    planets[name] = node
                }
            }
        }
        linkedMapOf("star" to star, "planets" to planets.values.toList())
    }
}