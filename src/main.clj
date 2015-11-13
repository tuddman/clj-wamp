 (ns main
  (:require [clj-wamp.node :as w]
            [clj-wamp.v2 :refer [message-chan]]
            [clojure.core.async
             :as async
             :refer [>! <! >!! <!! go go-loop chan buffer close! thread
                     alts! alts!! timeout sub]]))

(def connector (w/create {:router-uri "ws://127.0.0.1:8180/ws" :realm "tour" :debug? true}))

(def connection (w/connect! connector))

(def subscriber-one (chan))

(defn take-and-print [channel prefix]
  (go-loop []
    (println prefix ": " (<! channel))
    (recur)))


(sub message-chan :RESULT subscriber-one)


(take-and-print subscriber-one "subscriber-one")

;(go (while true (println "Message " (<! message-chan))))