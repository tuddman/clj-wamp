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
    [clj-wamp.info.ids :refer [reverse-message-id message-id]]
    [clj-wamp.info.uris :refer [error-uri]]
    [clj-wamp.client.handler.error :refer [handle-error]]
    [clj-wamp.libs.channels :refer [messages]]
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
            :subscriber {:features
                         {
                          :caller_identification    true
                          :progressive_call_results true
                          }
                         }
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

(defn publish
  "[PUBLISH, Request|id, Options|dict, Topic|uri]
   [PUBLISH, Request|id, Options|dict, Topic|uri, Arguments|list]
   [PUBLISH, Request|id, Options|dict, Topic|uri, Arguments|list, ArgumentsKw|dict]"
  [instance request-id options uri seq-args kw-args]
  (let [args [(message-id :PUBLISH) request-id options uri]
        args (if (some? seq-args) (conj args seq-args) args)
        args (if (some? kw-args) (conj args kw-args) args)]
  (send! instance args)))

(defn register
  "[REGISTER, Request|id, Options|dict, Procedure|uri]"
  [instance request-id options uri]
  (send! instance [(message-id :REGISTER) request-id options uri]))

(defn unregister
  "[UNREGISTER, Request|id, REGISTERED.Registration|id]"
  [instance request-id reg-uri]
  (send! instance [(message-id :UNREGISTER) request-id reg-uri]))

(defn call
  "[CALL, Request|id, Options|dict, Procedure|uri]
   [CALL, Request|id, Options|dict, Procedure|uri, Arguments|list]
   [CALL, Request|id, Options|dict, Procedure|uri, Arguments|list,ArgumentsKw|dict]

   'Request' is a random, ephemeral ID chosen by the Caller and
   used to correlate the Dealer's response with the request.

      o  'Options' is a dictionary that allows to provide additional call
   request details in an extensible way.  This is described further
   below.

      o  'Procedure' is the URI of the procedure to be called.

      o  'Arguments' is a list of positional call arguments (each of
   arbitrary type).  The list may be of zero length.

      o  'ArgumentsKw' is a dictionary of keyword call arguments (each of
   arbitrary type).  The dictionary may be empty."

  [instance request-id options uri seq-args kw-args]
  (let [args [(message-id :CALL) request-id options uri]
        args (if (some? seq-args) (conj args seq-args) args)
        args (if (some? kw-args) (conj args kw-args) args)]
    (send! instance args)))


(defn yield
  "[YIELD, INVOCATION.Request|id, Options|dict]
   [YIELD, INVOCATION.Request|id, Options|dict, Arguments|list]
   [YIELD, INVOCATION.Request|id, Options|dict, Arguments|list, ArgumentsKw|dict]"
  [instance request-id options seq-args kw-args]
  (let [args [(message-id :YIELD) request-id options]
        args (if (some? seq-args) (conj args seq-args) args)
        args (if (some? kw-args) (conj args kw-args) args)]
  (send! instance args)))

(defn register-next!
  [instance]
  (swap! (:registrations instance)
         (fn [[unregistered registered pending]]
           (if-let [[reg-uri reg-fn] (first unregistered)]
             (let [req-id (core/new-rand-id)]
               (register instance req-id {} reg-uri)
               [(dissoc unregistered reg-uri) registered (assoc pending req-id [reg-uri reg-fn])])
             [unregistered registered pending]))))

(defn register-new!
  "Associates the uri to the function as unregistred command. If the uri is already registred,
  it returns the existing map"
  [instance reg-uri reg-fn]
  (swap! (:registrations instance)
         (fn [[unregistered registered pending :as registrations]]
           (if-not (lib/contains-nested? (lib/map->vec registrations) #(= % reg-uri))
             [(assoc unregistered reg-uri reg-fn) registered pending]
             [unregistered registered pending])
           ))
  (register-next! instance)
  )

(defn unregister!
  "Deassociates the procedure with the id and sends the unregister message to the server"
  [instance reg-uri]
  (swap! (:registrations instance)
         (fn [[unregistered registered pending]]
           (if-let [[reg-id [_ _]] (lib/finds-nested registered reg-uri)]
             (let [req-id (core/new-rand-id)]
               (unregister instance req-id reg-id)
               [unregistered registered (assoc pending req-id [reg-id reg-uri])])
             [unregistered registered pending])
             )))

(defn- exception-message
  [{:keys [debug?] :as instance} ex]
  (if debug?
    {:message (.getMessage ex)
     :stacktrace (map str (.getStackTrace ex))}
    {:message "Application error"}))

(declare yield-progressive)

(defn- yield-return
  [instance req-id return]
  (if-let [return-error (:error return)]
    (let [return-error-uri (if (:uri return-error) (:uri return-error) (error-uri :application-error))]
      (error instance (message-id :INVOCATION) req-id (dissoc return-error :uri) return-error-uri))
    (cond
      (:result return) (yield instance req-id (merge {} (:options return)) [(:result return)] nil)
      (:seq-result return) (yield instance req-id (merge {} (:options return)) (vec (:seq-result return)) nil)
      (:map-result return) (yield instance req-id (merge {} (:options return)) [] (:map-result return))
      (:chan-result return) (yield-progressive instance req-id (:chan-result return))
      :else (yield instance req-id {} [return] nil))))

(defn- yield-progressive
  [instance req-id return-chan]
  (go-loop []
    (when-let [return (<! return-chan)]
      (yield-return instance req-id return)
      (recur))))

(defn perform-invocation
  [instance req-id rpc-fn options seq-args map-args]
  (try 
    (let [return (rpc-fn {:call-id req-id
                          :options options
                          :seq-args seq-args
                          :map-args map-args})]
      (yield-return instance req-id return))
    (catch Throwable e
      (error instance (message-id :INVOCATION) req-id (exception-message instance e) (error-uri :internal-error)))))
