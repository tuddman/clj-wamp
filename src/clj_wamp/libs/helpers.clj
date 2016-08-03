(ns clj-wamp.libs.helpers)

(defn contains-nested?
  [m k]
  (->> (tree-seq vector? seq m)
       (filter #(not (vector? %)))
       (some k)))


(defn finds-nested
  [m k]
  (->> (filter (fn [[_ [child]]] (= child k)) m)
       (first)))


(defn map->vec
  "Converts map into vector, removes nil Values"
  [map]
  (->> map
       (filter #(not (nil? %)))
       (first)
       (vals)
       (into [])))
