(ns modex.mcp.datomic
  (:require [taoensso.timbre :as log]
            [modex.mcp.protocols :as mcp]
            [modex.mcp.server :as server]
            [modex.mcp.tools :as tools]
            [clojure.pprint :as pprint]
            [clojure.edn :as edn]
            [datomic.api :as d])
  (:gen-class)
  (:import (java.util.logging ConsoleHandler Level Logger StreamHandler)))

(def !conn (atom nil))

(def datomic-uri (System/getenv "DATOMIC_URI"))

(defn redirect-datomic-logs-to-stderr! []
  (let [root-logger    (Logger/getLogger "")
        handlers       (.getHandlers root-logger)
        stderr-handler (StreamHandler. System/err (java.util.logging.SimpleFormatter.))]

    ;; Remove existing handlers
    (doseq [handler handlers]
      (.removeHandler root-logger handler))

    ;; Add stderr handler
    (.addHandler root-logger stderr-handler)

    ;; Set desired log level
    (.setLevel root-logger Level/INFO)))                    ;; Set root logger level

(redirect-datomic-logs-to-stderr!)

(defn connect!
  "This happens on tool init because connecting to prod Datomic can timeout client."
  [!conn datomic-uri]
  (log/info "Connecting to Datomic...")
  (let [conn (d/connect datomic-uri)]
    (log/info "Connected to Datomic.")
    (reset! !conn conn)))

(comment                                                    ; todo: impl. shutdown in Modex.
  (.release conn))

;(def conn conn2)

(defn query-schema
  "Returns list of user-space schema."
  [db]
  (d/q '[:find [(pull ?a [*]) ...]
         :where
         [_ :db.install/attribute ?a]
         [?a :db/valueType ?valueType]
         [?a :db/cardinality ?c]
         [?a :db/ident ?attr]
         [?valueType :db/ident ?type]
         [?c :db/ident ?cardinality]]
       db))

(comment
  (query-schema (d/db conn)))

(defn singular-value?
  "d/q can return scalar values, maps, a map, or a seq,
  so we need to figure out if a tool is returning a single element (map or scalar),
  a vector, list or seq of values."
  [x]
  (or (map? x)
      (number? x)
      (string? x)
      (boolean? x)
      (keyword? x)
      (symbol? x)
      (nil? x)
      (and (not (coll? x))
           (not (seq? x)))))

(comment
  (singular-value? 567)
  (singular-value? {:a 5})                                  ; true
  (singular-value? [{:a 5}])                                ; false
  (singular-value? [{:a 5}]))

(def set-datomic-indices #{:eavt :aevt :avet :vaet})

(defn q-handler
  [db {:keys [qry args offset limit]
       :or   {offset 0
              limit  100}}]
  (let [parsed-qry  (edn/read-string qry)
        parsed-args (edn/read-string args)
        result      (->> (apply d/q parsed-qry db parsed-args) (drop offset) (take limit))]
    (map pr-str (if (singular-value? result)
                  [result]
                  result))))

(def datomic-tools
  "Define your tools here."
  (tools/tools

    (schema
      "Queries Datomic Schema and returns a seq of EDN maps."
      []
      (query-schema (d/db @!conn)))

    (entid
      "Returns the entity id associated with a symbolic keyword, or the id itself if passed.

      Runs: (->> (datomic.api/entid db ident) (pr-str))."
      [{:keys [ident]
        :type {ident :string}
        :doc  {ident "EDN-encoded string with Datomic ident, :db/ident keyword or eid, e.g. 123, [:unique/ident :abc] or :db/ident value."}}]
      (->> (edn/read-string ident)
           (d/entid (d/db @!conn))
           (pr-str)
           (vector)))

    (entity
      "(->> (datomic.api/entity db eid) (pr-str))"
      [{:keys [eid]
        :type {eid :number}
        :doc  {eid "Datomic :db/id entity ID (Long)"}}]
      [(pr-str (d/entity (d/db @!conn) eid))])

    (touch
      "Runs (->> (datomic.api/entity db eid) (datomic.api/touch) (pr-str))."
      [{:keys [eid]
        :type {eid :number}
        :doc  {eid "Datomic :db/id entity ID (Long)"}}]
      (let [db (d/db @!conn)]
        (->> eid
             (d/entity db)
             (d/touch)
             (pr-str)
             (vector))))

    (pull
      "Runs (->> (datomic.api/pull db pattern eid) (pr-str)).
      Refer to schema tool for valid patterns."
      [{:keys [pattern eid]
        :type {pattern :string
               eid     :number}
        :doc  {pattern "EDN-encoded Datalog pattern, e.g. '[*]'"
               eid     "Datomic :db/id entity ID (Long) to pull."}}]
      [(pr-str (d/pull (d/db @!conn) pattern eid))])

    (pull-many
      "Datomic pull-many.
      Runs (->> eids (datomic.api/pull-many db pattern) (mapv pr-str)).

      Refer to schema tool for valid patterns."
      [{:keys [pattern eids]
        :type {pattern :string
               eids    :string}
        :doc  {pattern "EDN-encoded Datalog pattern, e.g. '[*]'"
               eids    "EDN-encoded vector of Datomic :db/id entity IDs (Longs) to send to pull-many, e.g. \"[1 2 3]\"."}}]
      (let [edn-pattern (edn/read-string pattern)
            edn-eids    (edn/read-string eids)]
        (->> edn-eids
             (d/pull-many (d/db @!conn) edn-pattern)
             (mapv pr-str))))

    (q
      "Query Datomic.
      Runs `(->> (apply datomic.api/q (d/db conn) qry args) (drop offset) (take limit))`.
      Takes qry, vector of args and limit. Returns a collection of results.

      Use the `schema` tool to learn valid attributes before attempting queries."
      [{:keys [qry args offset limit]
        :type {qry    :string
               args   :string
               offset :number
               limit  :number}
        :or   {limit  100
               offset 0}
        :doc  {qry    "EDN-encoded Datalog Query, e.g. \"[:find ?e :where [?e :some/attr ?value]]\""
               args   "EDN-encoded vector of Datalog Query Arguments, e.g. \"[1 2 3]\""
               offset "Query offset: (->> qry-result (drop offset)). Defaults to 0."
               limit  "Query limit: (->> qry-result (drop offset) (take limit)). Defaults to 100."}}]
      (q-handler (d/db @!conn) {:qry qry :args args :offset offset :limit limit}))

    (q-with
      "Like `q` tool, but prospectively applies tx-data using `datomic.api/with` and queries against db-after.

      This runs:
      ```
      (let [db-after (-> (d/with db tx-data) :db-after)]
         (->> (apply datomic.api/q qry db-after args)
              (drop offset)
              (take limit)))
      ```

      Accepts {:keys [tx-data qry args offset limit]} & returns a collection of results.

      tx-data, qry & args are EDN-encoded strings. offset & limit are numbers.

      Use the `schema` tool to learn valid attributes before attempting to write queries. "
      [{:keys [tx-data
               qry args
               offset limit]
        :type {tx-data :string
               qry     :string
               args    :string
               offset  :number
               limit   :number}
        :or   {offset 0
               limit  100}
        :doc  {tx-data "EDN-encoded tx-data collection is passed to datomic.api/with, e.g. \"[{:db/id 1234 :some/attr \"new-value\"} [:db/add ...] [:db/retract ...]]\""
               qry     "EDN-encoded Datalog Query, e.g. \"[:find ?e :where [?e :some/attr ?value]]\""
               args    "EDN-encoded vector of Datalog Query Arguments, e.g. \"[1 2 3]\""
               offset  "Query offset: (->> qry-result (drop offset)). Defaults to 0."
               limit   "Query limit: (->> qry-result (drop offset) (take limit)). Defaults to 100."}}]
      (let [parsed-tx-data (clojure.edn/read-string tx-data)
            db-before      (d/db @!conn)
            {:as _rx :keys [db-after]} (d/with db-before parsed-tx-data)]
        (q-handler db-after {:qry qry :args args :offset offset :limit limit})))

    (datoms
      "Runs `(datomic.api/datoms db index & components)` and returns an iterable of datoms,
      where index is one of :eavt, :aevt, :avet or :vaet. Not all indices may be available.

      datoms provides raw access to the index data, by index. The index must be supplied,
and, optionally, one or more leading components of the index can be
supplied to narrow the result.

- :eavt and :aevt indexes will contain all datoms.
- :avet contains datoms for attributes where :db/index = true (may not be available).
- :vaet contains datoms for attributes of :db.type/ref.
- :vaet is the reverse index.

      E.g. (d/datoms db :aevt :server/name) will return datoms where entity has :server/name attribute.

      Use `schema` tool to learn valid attributes before attempting to write queries. "
      [{:keys [index components offset limit]
        :doc  {index      "EDN-encoded keyword. One of `:eavt`, `:aevt`, `:avet` or `:vaet`. `:avet` is only available if attr is indexed."
               components "EDN-encoded vector of `components` passed to d/datoms, e.g. \"[:server/name \"server name\"]\""
               offset     "Offset: (->> datoms (drop offset)). Defaults to 0."
               limit      "Limit: (->> datoms (drop offset) (take limit)). Defaults to 100."}
        :type {index      :string
               components :string
               offset     :number
               limit      :number}
        :or   {offset 0
               limit  100}}]
      (let [parsed-index      (edn/read-string index)       ; should be keyword.
            _                 (assert (keyword? parsed-index) "index must be keyword.")
            _                 (assert (get set-datomic-indices parsed-index) (str "index must be one of: " (pr-str set-datomic-indices)))
            parsed-components (edn/read-string components)
            db                (d/db @!conn)                 ; todo as-of.
            datoms            (->> (apply d/datoms db parsed-index parsed-components)
                                   (drop (or offset 0))
                                   (take (or limit 100)))]
        (map pr-str datoms)))))

(comment
  datomic-tools)

(defn make-datomic-mcp-server
  "Here we create a reified instance of AServer. Only tools are presently supported."
  [datomic-uri]
  (server/->server
    {:name       "Datomic MCP Server (Modex)"
     :version    "0.0.1"
     :tools      datomic-tools
     :initialize (fn [init-params] (connect! !conn datomic-uri)) ; is called in future.
     ; todo shutdown.
     :prompts    nil
     :resources  nil}))

(comment
  (def in-mem-datomic-uri "datomic:mem://datomic-mcp")
  (d/create-database in-mem-datomic-uri)

  (def *server (make-datomic-mcp-server in-mem-datomic-uri))

  (mcp/list-tools *server)

  (require '[clojure.repl :refer [doc source]])
  (source mcp/call-tool)

  (mcp/initialize *server)                                  ; connects
  ;
  (server/handle-request
    *server
    {:method  "tools/call"
     :params  {:name      "q"
               :arguments {:qry   "[:find ?ident :where [?e :db/ident ?ident]]",
                           :args  "[]"
                           ;:offset 0                        ; fix this error!
                           :limit 10}}
     :jsonrpc "2.0"
     :id      44})

  (require '[modex.mcp.tools :as tools])

  ; invoke-handler returns results directly:
  (tools/invoke-handler
    (get-in datomic-tools [:datoms :handler])
    {:components "[1]"
     :index      ":eavt"
     :limit      100
     :offset     0})

  (mcp/call-tool *server :q {:qry    "[:find ?e ?ident :where [?e :db/ident ?ident]]"
                             :args   nil
                             :offset 0
                             :limit  100})

  ; mcp/call-tool wraps results in {:keys [success results ?errors]}
  (mcp/call-tool *server :datoms {:components "[1]"
                                  :index      ":eavt"
                                  :offset     0
                                  :limit      100})

  (mcp/initialize datomic-mcp-server))

(defn -main
  "Starts a Datomic MCP server that talks JSON-RPC over stdio/stdout."
  [& args]
  (log/debug "Server starting via -main")
  (try
    (let [mcp-server (make-datomic-mcp-server datomic-uri)]
      (server/start-server! mcp-server))
    (catch Throwable t
      (log/debug "Fatal error in -main:" (.getMessage t))
      (.printStackTrace t (java.io.PrintWriter. *err*)))))