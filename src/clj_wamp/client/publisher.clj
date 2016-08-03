(ns clj-wamp.client.publisher
  (:require
    [clojure.core.async
     :refer [>! <! >!! <!! go go-loop chan buffer close! thread
             alts! alts!! timeout pub]]
    [clj-wamp.client.node :refer [send!]]
    [clj-wamp.info.ids :refer [message-id]] ))


(defn publish
  "[PUBLISH, Request|id, Options|dict, Topic|uri]
   [PUBLISH, Request|id, Options|dict, Topic|uri, Arguments|list]
   [PUBLISH, Request|id, Options|dict, Topic|uri, Arguments|list, ArgumentsKw|dict]"
  [instance request-id options uri seq-args kw-args]
  (let [args [(message-id :PUBLISH) request-id options uri]
        args (if (some? seq-args) (conj args seq-args) args)
        args (if (some? kw-args) (conj args kw-args) args)]
    (send! instance args)))

