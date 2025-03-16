(ns modex.mcp.datomic
  (:require [taoensso.timbre :as log]
            [modex.mcp.protocols :as mcp]
            [modex.mcp.server :as server]
            [modex.mcp.tools :as tools]
            [clojure.pprint :as pprint]
            [clojure.edn :as edn]
            [datomic.api :as d])
  (:gen-class))

(def !conn (atom nil))

(def datomic-uri (System/getenv "DATOMIC_URI"))

(defn connect!
  "This happens on tool init because connecting to prod Datomic can timeout client."
  [!conn datomic-uri]
  (reset! !conn (d/connect datomic-uri)))

(comment ; todo: impl. shutdown in Modex.
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
  (singular-value? {:a 5}) ; true
  (singular-value? [{:a 5}]) ; false
  (singular-value? [{:a 5}]))

(def set-datomic-indices #{:eavt :aevt :avet :vaet})

(def datomic-tools
  "Define your tools here."
  (tools/tools

    (schema
      "Queries Datomic Schema and returns a seq of EDN maps."
      []
      (query-schema (d/db @!conn)))

    (q
      "Queries Datomic via `(take limit (apply datomic.api/q (d/db conn) qry args))`.
      Takes qry, vector of args and limit. Returns a collection of results.
      Use `schema` tool to learn valid attributes before attempting to write queries. "
      [^{:type :string :doc "EDN-encoded Datalog Query, e.g. \"[:find ?e :where [?e :some/attr ?value]]\""} qry
       ^{:type :string :doc "EDN-encoded vector of Datalog Query Arguments, e.g. \"[1 2 3]\""} args
       ^{:type :number :doc "Query limit. Used via (take limit query-result). Defaults to 100."} limit]
      (let [parsed-qry  (edn/read-string qry)
            parsed-args (edn/read-string args)
            db          (d/db @!conn)
            _ (prn 'running-query parsed-qry)
            qry-result  (take (or limit 100) (apply d/q parsed-qry db parsed-args))]
        ; parse qry & args.
        ; ; because d/q can return single result, we need to support scalars
        (if (singular-value? qry-result)
          [qry-result]
          qry-result)))

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
      [^{:type :string :doc "EDN-encoded keyword. One of `:eavt`, `:aevt`, `:avet` (if :db/index = true) or `:vaet`"} index
       ^{:type :string :doc "EDN-encoded vector of `components` passed to d/datoms, e.g. \"[:server/name 123]\""} components
       ^{:type :number :doc "(->> datoms (drop offset)). Defaults to 0.", :required false} offset
       ^{:type :number :doc "(->> datoms (drop offset) (take limit)). Defaults to 100.", :required false} limit]
      (let [parsed-index      (edn/read-string index) ; should be keyword.
            _                 (assert (keyword? parsed-index) "index must be keyword.")
            _                 (assert (get set-datomic-indices parsed-index) (str "index must be one of: " (pr-str set-datomic-indices)))
            parsed-components (edn/read-string components)
            db                (d/db @!conn) ; todo as-of.
            ; todo pagination.
            datoms            (->> (apply d/datoms db parsed-index parsed-components)
                                   (drop (or offset 0))
                                   (take (or limit 100)))]
        datoms))))

(def datomic-mcp-server
  "Here we create a reified instance of AServer. Only tools are presently supported."
  (server/->server
    {:name      "Datomic MCP Server (Modex)"
     :version   "0.0.1"
     :tools     datomic-tools
     :initialize (fn [] (connect! !conn datomic-uri))
     ; todo shutdown.
     :prompts   nil
     :resources nil}))

(comment
  (mcp/initialize datomic-mcp-server))

(defn -main
  "Starts a Datomic MCP server that talks JSON-RPC over stdio/stdout."
  [& args]
  (log/debug "Server starting via -main")
  (try
    (server/start-server! datomic-mcp-server)
    (catch Throwable t
      (log/debug "Fatal error in -main:" (.getMessage t))
      (.printStackTrace t (java.io.PrintWriter. *err*)))))