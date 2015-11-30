(ns clj-wamp.client.v2
  (:require
    [clojure.core.async
      :refer [>! <! >!! <!! go go-loop chan buffer close! thread
                                          alts! alts!! timeout pub]]
    [taoensso.timbre :as log]
    [cheshire.core :as json]
    [gniazdo.core :as ws]
    [clj-wamp.core :as core]
    [clj-wamp.libs.helpers :as lib]
    [clj-wamp.info.ids :refer [message-id]]
    ))

(def subprotocol-id "wamp.2.json")

(defn new-request
  []
  (core/new-rand-id))

(defn send!
  [{:keys [debug?] :as instance} msg-data]
  (let [json-str (json/encode msg-data)]
    (when debug?
      (log/debug "Sending WAMP message" json-str))
    (when-let [socket @(:socket instance)]
      (ws/send-msg socket json-str))))

(defn hello
  "[HELLO, Realm|uri, Details|dict]"
  [instance]
  (send! instance
         [(message-id :HELLO)
          (:realm instance)
          {:roles
           {:callee     {:features
                         {
                          :caller_identification      true
                          :pattern_based_registration true
                          :progressive_call_results   true
                          :registration_revocation    true
                          :shared_registration        true
                          }
                         }
            :caller     {:features
                         {
                          :caller_identification    true
                          :progressive_call_results true
                          }
                         }
            ;:subscriber {:features
            ;             {
            ;              :caller_identification    true
            ;              :progressive_call_results true
            ;              }
            ;             }
            :publisher
                        {:features
                         {
                          :subscriber_blackwhite_listing true
                          :publisher_exclusion           true
                          :publisher_identification      true
                          }
                         }}}]))

(defn abort
  "[ABORT, Details|dict, Reason|uri]"
  [instance details uri]
  (send! instance [(message-id :ABORT) details uri]))

(defn goodbye
  "[GOODBYE, Details|dict, Reason|uri]"
  [instance details uri]
  (send! instance [(message-id :GOODBYE) details uri]))

(defn error
 "[ERROR, REQUEST.Type|int, REQUEST.Request|id, Details|dict, Error|uri]
  [ERROR, REQUEST.Type|int, REQUEST.Request|id, Details|dict, Error|uri, Arguments|list]
  [ERROR, REQUEST.Type|int, REQUEST.Request|id, Details|dict, Error|uri, Arguments|list, ArgumentsKw|dict]" 
  [instance request-type request-id details uri]
  (send! instance
         [(message-id :ERROR) request-type request-id details uri]))

(defn- exception-message
  [{:keys [debug?] :as instance} ex]
  (if debug?
    {:message (.getMessage ex)
     :stacktrace (map str (.getStackTrace ex))}
    {:message "Application error"}))

