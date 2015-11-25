(ns clj-wamp.lib)


(defn contains-nested?
  [m k]
  (->> (tree-seq vector? seq m)
       (filter #(not (vector? %)))
       (some k)
       ))

(defn map->vec
  "Converts map into vector, removes nil Values"
  [map]
  (->> map
       (filter #(not (nil? %)))
       (first)
       (vals)
       (into []))
  )