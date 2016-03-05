(ns clj-wamp.server-v2
  ^{:author "Christopher Martin, Ryan Sundberg"
    :doc "Clojure implementation of the WebSocket Application Messaging Protocol, version 2"}
  (:use [clojure.core.incubator :only [dissoc-in]]
        [clojure.string :only [split trim lower-case]])
  (:require [clojure.java.io :as io]
            [org.httpkit.server :as httpkit]
            [org.httpkit.timer :as timer]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [clojure.data.codec.base64 :as base64]
            [clj-wamp.core :as core]
            [clj-wamp.v2 :as wamp2])
  (:import [org.httpkit.server AsyncChannel]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

;; Topic utils

(def client-topics (ref {}))
(def topic-clients (ref {}))


(defn send-subscribed!
  "Sends a WAMP SUBSCRIBED message to a websocket client.
         [SUBSCRIBED, SUBSCRIBE.Request|id, Subscription|id]"
  [sess-id request-id subscription-id]
  (core/send! sess-id (wamp2/message-id :SUBSCRIBED) request-id subscription-id))

(defn topic-subscribe
  "Subscribes a websocket session to a topic."
  [topic sess-id request-id]
  (dosync
    (alter topic-clients assoc-in [topic sess-id] true)
    (alter client-topics assoc-in [sess-id topic] true))
  (send-subscribed! sess-id request-id topic))

(defn topic-unsubscribe
  "Unsubscribes a websocket session from a topic."
  [topic sess-id]
  (dosync
    (alter topic-clients dissoc-in [topic sess-id])
    (alter client-topics dissoc-in [sess-id topic])))

(defn- topic-send!
  "Sends an event to *all* websocket clients subscribed to a topic."
  [topic & data]
  (dosync
    (doseq [[sess-id _] (@topic-clients topic)]
      (apply core/send! sess-id data))))

(defn- topic-broadcast!
  "Send an event to websocket clients subscribed to a topic,
  except those excluded."
  [topic excludes & data]
  (let [excludes (if (sequential? excludes) excludes [excludes])]
    (dosync
      (doseq [[sess-id _] (@topic-clients topic)]
        (if (not-any? #{sess-id} excludes)
          (apply core/send! sess-id data))))))

(defn- topic-emit!
  "Sends an event to a specific list of websocket clients subscribed
  to a topic."
  [topic includes & data]
  (let [includes (if (sequential? includes) includes [includes])]
    (dosync
      (let [clients   (@topic-clients topic)]
        (doseq [[sess-id _] clients]
          (if (some #{sess-id} includes)
            (apply core/send! sess-id data)))))))

(defn get-topic-clients [topic]
  "Returns all client session ids within a topic."
  (if-let [clients (@topic-clients topic)]
    (keys clients)))

;; WAMP websocket send! utils

(defn send-welcome!
  "Sends a WAMP welcome message to a websocket client.
  [WELCOME, Session|id, Details|dict]"
  [sess-id]
  (core/send! sess-id (wamp2/message-id :WELCOME) sess-id
              {:version core/project-version
               :roles
               {:dealer {}
                ;:callee {}
                }}))


(defn send-abort!
  "Sends an ABORT message to abort opening a session."
  [sess-id details-dict reason-uri]
  (core/send! sess-id (wamp2/message-id :ABORT) details-dict reason-uri))

(defn send-goodbye!
  "Send a GOODBYE message"
  [sess-id details-dict reason-uri]
  (core/send! sess-id (wamp2/message-id :GOODBYE) details-dict reason-uri))

(defn send-call-result!
  "Sends a WAMP call result message to a websocket client.
   [RESULT, CALL.Request|id, Details|dict]
   [RESULT, CALL.Request|id, Details|dict, YIELD.Arguments|list]
   [RESULT, CALL.Request|id, Details|dict, YIELD.Arguments|list, YIELD.ArgumentsKw|dict]"
  [sess-id call-id result]
  (core/send! sess-id (wamp2/message-id :RESULT) call-id {} [] result))

(defn send-error!
  "Sends a WAMP call error message to a websocket client.
   [ERROR, CALL, CALL.Request|id, Details|dict, Error|uri]
   [ERROR, CALL, CALL.Request|id, Details|dict, Error|uri, Arguments|list]
   [ERROR, CALL, CALL.Request|id, Details|dict, Error|uri, Arguments|list, ArgumentsKw|dict]"
  [sess-id call-id error-info error-uri]
  (core/send! sess-id (wamp2/message-id :ERROR) call-id error-info error-uri))

(defn send-event!
  "Sends an event message to all clients in topic.
     [EVENT, SUBSCRIBED.Subscription|id, PUBLISHED.Publication|id,
          Details|dict]
     [EVENT, SUBSCRIBED.Subscription|id, PUBLISHED.Publication|id,
          Details|dict, PUBLISH.Arguments|list]
     [EVENT, SUBSCRIBED.Subscription|id, PUBLISHED.Publication|id,
          Details|dict, PUBLISH.Arguments|list, PUBLISH.ArgumentKw|dict]"
  [topic event]
  (topic-send! topic (wamp2/message-id :EVENT) topic {} [] event))

(defn broadcast-event!
  "Sends an event message to all clients in a topic but those excluded."
  [topic event excludes]
    (topic-broadcast! topic excludes (wamp2/message-id :EVENT) topic {} [] event))

(defn emit-event!
  "Sends an event message to specific clients in a topic"
  [topic event includes]
    (topic-emit! topic includes (wamp2/message-id :EVENT) topic {} [] event))

;; WAMP callbacks

(defn- callback-rewrite
  "Utility for rewriting params with an optional callback fn."
  [callback & params]
  (if (fn? callback)
    (apply callback params)
    (when (or (nil? callback) (true? callback))
      params)))

(defn- on-close
  "Clean up clients and topics upon disconnect."
  [sess-id close-cb unsub-cb]
  (fn [status]
    (println "#### on-close")
    (when (fn? close-cb) (close-cb sess-id status))
    (doseq [topic (dosync
                    (if-let [sess-topics (@client-topics sess-id)]
                      (for [[topic _] sess-topics]
                        (do
                          (topic-unsubscribe topic sess-id)
                          topic))))]
      (when (fn? unsub-cb) (unsub-cb sess-id topic)))
    (core/del-client sess-id)))

(defn- call-success
  [sess-id topic call-id result on-after-cb]
  (let [cb-params [sess-id topic call-id result]
        cb-params (apply callback-rewrite on-after-cb cb-params)
        [sess-id topic call-id result] cb-params]
    (send-call-result! sess-id call-id result)))

(defn- call-error
  [sess-id topic call-id error on-after-cb]
  (let [cb-params [sess-id topic call-id error]
        cb-params (apply callback-rewrite on-after-cb cb-params)
        [sess-id topic call-id error] cb-params
        {err-uri :uri err-msg :message err-desc :description kill :kill} error
        err-uri (if (nil? err-uri) (wamp2/error-uri :application-error) err-uri)
        err-msg (if (nil? err-msg) "Application error" err-msg)]
    (println "\nLLLOOOGGG" "call-error" error)

    (send-error! sess-id call-id {:message err-msg
                                  :description err-desc} err-uri)
    (when kill (core/close-channel sess-id))))

; Optional session id for rpc calls
(def ^:dynamic *call-sess-id* nil)

;; WAMP-CRA Authentication

(defn hmac-sha-256
  "Generates a HMAC SHA256 hash."
  [^String key ^String data]
  (let [hmac-key (SecretKeySpec. (.getBytes key) "HmacSHA256")
        hmac     (doto (Mac/getInstance "HmacSHA256") (.init hmac-key))
        result   (.doFinal hmac (.getBytes data))]
    (String. (base64/encode result) "UTF-8")))

(defn auth-challenge
  "Generates a challenge hash used by the client to sign the secret."
  [sess-id auth-key auth-secret]
  (throw (Exception. "Fix hazard here"))
  (let [hmac-key (str auth-secret "-" (System/currentTimeMillis) "-" sess-id)]
    (hmac-sha-256 hmac-key auth-key)))

(defn- auth-sig-match?
  "Check whether the client signature matches the server's signature."
  [sess-id signature]
  (if-let [auth-sig (get-in @core/client-auth [sess-id :sig])]
    (= signature auth-sig)))

(defn- add-client-auth-sig
  "Stores the authorization signature on the server-side for later
  comparison with the client."
  [sess-id auth-key auth-secret challenge]
  (let [sig (hmac-sha-256 challenge auth-secret)]
    (dosync
      (alter core/client-auth assoc sess-id {:sig   sig
                                        :key   auth-key
                                        :auth? false}))
    sig))

(defn- add-client-auth-anon
  "Stores anonymous client metadata with the session."
  [sess-id]
  (dosync (alter core/client-auth assoc sess-id {:key :anon :auth? false})))

(defn client-auth-requested?
  "Checks if the authreq call has already occurred."
  [sess-id]
  (not (nil? (get-in @core/client-auth [sess-id :key]))))

(defn client-authenticated?
  "Checks if authentication has occurred."
  [sess-id]
  (get-in @core/client-auth [sess-id :auth?])
  true)

(defn- permission?
  "Checks if a topic and category has permissions in a permission map.
    {:all true} ; or
    {:rpc true} ; or
    {:rpc {\"http://mytopic\" true}"
  [perms category topic]
  (or (get perms :all)
    (true? (get perms category))
    (get-in perms [category topic])))

(defn authorized?
  "Checks if the session is authorized for a message category and topic."
  [sess-id category topic perm-cb]
  (if-let [auth-key (get-in @core/client-auth [sess-id :key])]
    (let [perms (perm-cb sess-id auth-key)]
      (permission? perms category topic)))
  true)

(defn- create-call-authreq
  "Creates a callback for the authreq RPC call."
  [allow-anon? secret-cb]
  (fn [& [auth-key extra]]
    (dosync
      (if (client-authenticated? *call-sess-id*)
        {:error {:uri (wamp2/error-uri :authorization-failed)
                 :message "already authenticated"}}
        (if (client-auth-requested? *call-sess-id*)
          {:error {:uri (wamp2/error-uri :authorization-failed)
                   :message "authentication request already issued - authentication pending"}}

          (if (nil? auth-key)
            ; Allow anonymous auth?
            (if-not allow-anon?
              {:error {:uri (wamp2/error-uri :not-authorized)
                       :message "authentication as anonymous is forbidden"}}
              (do
                (add-client-auth-anon *call-sess-id*)
                nil)) ; return nil
            ; Non-anonymous auth
            (if-let [auth-secret (secret-cb *call-sess-id* auth-key extra)]
              (let [challenge (auth-challenge *call-sess-id* auth-key auth-secret)]
                (add-client-auth-sig *call-sess-id* auth-key auth-secret challenge)
                challenge) ; return the challenge
              {:error {:uri (wamp2/error-uri :authorization-failed)
                       :message "authentication key does not exist"}})))))))

(defn- expand-auth-perms
  "Expands shorthand permission maps into full topic permissions."
  [perms wamp-cbs]
  (let [wamp-perm-keys {:on-call      :rpc,
                        :on-subscribe :subscribe,
                        :on-publish   :publish}]
    (reduce conj
      (for [[wamp-key perm-key] wamp-perm-keys]
        {perm-key
         (into {}
           (remove nil?
             (for [[topic _] (get wamp-cbs wamp-key)]
               (if (permission? perms perm-key topic)
                 {topic true}))))}))))

(defn- create-call-auth
  "Creates a callback for the auth RPC call."
  [perm-cb wamp-cbs]
  (fn [& [signature]]
    (dosync
      (if (client-authenticated? *call-sess-id*)
        {:error {:uri (wamp2/error-uri :authorization-failed)
                 :message "already authenticated"}}
        (if (not (client-auth-requested? *call-sess-id*))
          {:error {:uri (wamp2/error-uri :authorization-failed)
                   :message "no authentication previously requested"}}
          (let [auth-key (get-in @core/client-auth [*call-sess-id* :key])]
            (if (or (= :anon auth-key) (auth-sig-match? *call-sess-id* signature))
              (do
                (alter core/client-auth assoc-in [*call-sess-id* :auth?] true)
                (expand-auth-perms (perm-cb *call-sess-id* auth-key) wamp-cbs))
              (do
                ; remove previous auth data, must request and authenticate again
                (alter core/client-auth dissoc *call-sess-id*)
                {:error {:uri (wamp2/error-uri :not-authorized)
                         :message "signature for authentication request is invalid"}}))))))))

(defn- init-cr-auth
  "Initializes the authorization RPC calls (if configured)."
  [callbacks]
  callbacks
  (if-let [auth-cbs (callbacks :on-auth)]
    (let [allow-anon? (auth-cbs :allow-anon?)
          secret-cb   (auth-cbs :secret)
          perm-cb     (auth-cbs :permissions)]
      (merge-with merge callbacks
        ; {:on-call {URI-WAMP-CALL-AUTHREQ (create-call-authreq allow-anon? secret-cb)
        ;            URI-WAMP-CALL-AUTH    (create-call-auth perm-cb callbacks)}}))
        {}))
    callbacks))

(defn- auth-timeout
  "Closes the session if the client has not authenticated."
  [sess-id]
  (when-not (client-authenticated? sess-id)
    (println "\nLLLOOOGGG" "auth-timeout")
    (core/close-channel sess-id)))

(defn- init-auth-timer
  "Ensure authentication occurs within a certain time period or else
  force close the session. Default timeout is 20000 ms (20 sec).
  Set timeout to 0 to disable."
  [callbacks sess-id]
  (when-let [auth-cbs (callbacks :on-auth)]
    (let [timeout-ms (auth-cbs :timeout 20000)]
      (if (> timeout-ms 0)
        (timer/schedule-task timeout-ms (auth-timeout sess-id))))))

;; WAMP PubSub/RPC callbacks

(defn- on-call
  "Handle WAMP call (RPC) messages"
  [callbacks sess-id topic call-id call-opts call-params call-kw-params]
  (if-let [rpc-cb (callbacks topic)]
    (try
      (let [call-params-list  [call-kw-params]
            cb-params         [sess-id topic call-id call-params-list]
            cb-params         (apply callback-rewrite (callbacks :on-before) cb-params)
            [sess-id topic call-id call-params-list]  cb-params
            rpc-result  (binding [*call-sess-id* sess-id]  ; bind optional sess-id
                          (apply rpc-cb call-params-list))      ; use fn's own arg signature
            error      (:error  rpc-result)
            result     (:result rpc-result)]
        (if (and (nil? error) (nil? result))
          ; No map with result or error? Assume successful rpc-result as-is
          (call-success sess-id topic call-id rpc-result (callbacks :on-after-success))
          (if (nil? error)
            (call-success sess-id topic call-id rpc-result (callbacks :on-after-success))
            (call-error   sess-id topic call-id error  (callbacks :on-after-error)))))
      (catch Exception e
        (call-error sess-id topic call-id
          {:uri (wamp2/error-uri :internal-error)
           :message "Internal error"
           :description (.getMessage e)}
          (callbacks :on-after-error))
        (println "\nLLLOOOGGG" "RPC Exception:" topic call-params call-kw-params e)))
    (call-error sess-id topic call-id
      {:uri (wamp2/error-uri :no-such-procedure)
       :message (str "No such procedure: '" topic "'")}
      (callbacks :on-after-error))))

(defn- map-key-or-prefix
  "Finds a map value by key or lookup by string key prefix (ending with *)."
  [m k]
  (if-let [v (m k)] v
    (some #(when (not (nil? %)) %)
      (for [[mk mv] m]
        (when (and (not (keyword? mk)) (not (false? mv))
                (= \* (last mk))
                (= (seq (take (dec (count mk)) k)) (butlast mk)))
          mv)))))

(defn- on-subscribe
  [callbacks sess-id topic request-id]
  (dosync
    (println "on-subscribe" @topic-clients topic)
    (when (nil? (get-in @topic-clients [topic sess-id]))
      (when-let [topic-cb (map-key-or-prefix callbacks topic)]
        (println "on-subscribe topic-cb" topic-cb)

        (when (or (true? topic-cb) (topic-cb sess-id topic))
          (let [on-after-cb (callbacks :on-after)]
            (topic-subscribe topic sess-id request-id)
            (when (fn? on-after-cb)
              (on-after-cb sess-id topic))))))))

(defn- get-publish-exclude [sess-id exclude]
  (if (= Boolean (type exclude))
    (when (true? exclude) [sess-id])
    exclude))

(defn- on-publish
  "Handles WAMP publish messages, sending event messages back out
  to clients subscribed to the topic."
  ([callbacks sess-id topic event]
    (on-publish callbacks sess-id topic event false nil))
  ([callbacks sess-id topic event exclude]
    (on-publish callbacks sess-id topic event exclude nil))
  ([callbacks sess-id topic event exclude eligible]
    (println "on-publish" topic event exclude eligible)
    (when-let [pub-cb (map-key-or-prefix callbacks topic)]
      (let [cb-params [sess-id topic event exclude eligible]
            cb-params (apply callback-rewrite pub-cb cb-params)
            on-after-cb (callbacks :on-after)]
        (when (sequential? cb-params)
          (let [[sess-id topic event exclude eligible] cb-params
                exclude (get-publish-exclude sess-id exclude)]
            (if-not (nil? eligible)
              (emit-event! topic event eligible)
              (broadcast-event! topic event exclude))
            (when (fn? on-after-cb)
              (on-after-cb sess-id topic event exclude eligible))))))))

(defmulti handle-message
  (fn [instance msg-type msg-params]
    (let [type-code (wamp2/reverse-message-id msg-type)]
      (println "handle-message" msg-type msg-params type-code)
      type-code)))

(defmethod handle-message nil
  [instance msg-type msg-params]
  (println "handle-message nil" msg-type msg-params))
  ; (error instance 0 0 {:message "Invalid message type"} (error-uri :bad-request)))

(defmethod handle-message :HELLO
  [instance msg-type msg-params]
  (do
    (send-welcome! (:sess-id instance))
  ))

(defmethod handle-message :GOODBYE
  [instance msg-type msg-params]
       ; [GOODBYE, Details|dict, Reason|uri]
  (do
    (println "handle-message :GOODBYE" msg-params)
    (core/close-channel (:sess-id instance))
  ))

(defmethod handle-message :CALL
  [instance msg-type msg-params]
  (let [sess-id       (:sess-id instance)
        callbacks     (:callbacks-map instance)
        perm-cb       (get-in callbacks [:on-auth :permissions])
        on-call-cbs   (:on-call callbacks)]
    ; "[CALL, Request|id, Options|dict, Procedure|uri]
    ;  [CALL, Request|id, Options|dict, Procedure|uri, Arguments|list]
    ;  [CALL, Request|id, Options|dict, Procedure|uri, Arguments|list, ArgumentsKw|dict]"

    (if (map? on-call-cbs)
      (let [[call-id call-opts topic-uri call-params & call-kw-params] msg-params
            topic (core/get-topic sess-id topic-uri)]
        (if (or (nil? perm-cb)
                ; (= URI-WAMP-CALL-AUTHREQ topic)
                ; (= URI-WAMP-CALL-AUTH topic)
                (authorized? sess-id :rpc topic perm-cb))
          (apply on-call on-call-cbs sess-id topic call-id call-opts call-params call-kw-params)
          (call-error sess-id topic call-id
            ; {:uri URI-WAMP-ERROR-NOAUTH :message DESC-WAMP-ERROR-NOAUTH}
            {:uri (wamp2/error-uri :not-authorized) :message (wamp2/error-uri :not-authorized)}
            (on-call-cbs :on-after-error)))))
))



(defmethod handle-message :SUBSCRIBE
  [instance msg-type msg-params]
  (let [sess-id       (:sess-id instance)
        callbacks     (:callbacks-map instance)
        perm-cb       (get-in callbacks [:on-auth :permissions])
        on-sub-cbs    (:on-subscribe callbacks)]
     ; [SUBSCRIBE, Request|id, Options|dict, Topic|uri]

    (let [request-id (first msg-params)
          details   (second msg-params)
          topic-uri (nth msg-params 2)
          topic     (core/get-topic sess-id topic-uri)]
      (println "SUBSCRIBE" request-id details topic-uri topic)
      (println "SUBSCRIBE (authorized? sess-id :subscribe topic perm-cb)" (authorized? sess-id :subscribe topic perm-cb))
      (if (or (nil? perm-cb) (authorized? sess-id :subscribe topic perm-cb))
        (on-subscribe on-sub-cbs sess-id topic request-id)))
))


(defmethod handle-message :UNSUBSCRIBE
  [instance msg-type msg-params]
  (let [sess-id       (:sess-id instance)
        callbacks     (:callbacks-map instance)
        on-unsub-cb   (:on-unsubscribe callbacks)]
     ; [UNSUBSCRIBE, Request|id, SUBSCRIBED.Subscription|id]

    (println "handle-message :UNSUBSCRIBE" msg-type msg-params on-unsub-cb)
    (let [request-id  (first msg-params)
          topic (core/get-topic sess-id (second msg-params))]
      (dosync
        (when (true? (get-in @topic-clients [topic sess-id]))
          (topic-unsubscribe topic sess-id)
          (when (fn? on-unsub-cb) (on-unsub-cb sess-id topic)))))))


(defmethod handle-message :PUBLISH
  [instance msg-type msg-params]
  (let [sess-id       (:sess-id instance)
        callbacks     (:callbacks-map instance)
        perm-cb       (get-in callbacks [:on-auth :permissions])
        on-pub-cbs    (:on-publish callbacks)]
     ; [PUBLISH, Request|id, Options|dict, Topic|uri]
     ; [PUBLISH, Request|id, Options|dict, Topic|uri, Arguments|list]
     ; [PUBLISH, Request|id, Options|dict, Topic|uri, Arguments|list,
     ;     ArgumentsKw|dict]

    (let [[request-id options topic-uri & pub-args] msg-params
          topic (core/get-topic sess-id topic-uri)]
      (println "handle-message :PUBLISH" request-id topic-uri pub-args)
      (if (or (nil? perm-cb) (authorized? sess-id :publish topic perm-cb))
        (apply on-publish on-pub-cbs sess-id topic pub-args)))))


(defn- handle-json-message [instance]
  (fn [msg-str]
    (let [[msg-type & msg-params] (try (json/decode msg-str)
                                    (catch com.fasterxml.jackson.core.JsonParseException ex
                                      [nil nil]))]
      (println "handle-json-message:" msg-str)
      (println "handle-json-message msg-type:" msg-type)
      (println "handle-json-message msg-params:" msg-params)
      (handle-message instance msg-type msg-params))))

(defn- create-instance [sess-id callbacks-map]
  {
    :sess-id            sess-id
    :callbacks-map      callbacks-map
  })

(defn http-kit-handler
  "Sets up the necessary http-kit websocket event handlers
  for use with the WAMP sub-protocol. Returns a WAMP client session id.

  Example usage:

    (http-kit/with-channel req channel
      (if-not (:websocket? req)
        (http-kit/close channel)
        (http-kit-handler channel
          {:on-open        on-open-fn
           :on-close       on-close-fn

           :on-auth        {:allow-anon?     false         ; allow anonymous authentication?
                            :timeout         20000         ; close connection if not authenticated
                                                           ; (default 20 secs)
                            :secret          auth-secret-fn
                            :permissions     auth-permissions-fn}

           :on-call        {(rpc-url \"add\")      +         ; map topics to rpc functions
                            (rpc-url \"echo\")     identity
                            :on-before           on-before-call-fn
                            :on-after-error      on-after-call-error-fn
                            :on-after-success    on-after-call-success-fn}

           :on-subscribe   {(evt-url \"chat\")     on-subscribe-fn? ; allowed to subscribe?
                            (evt-url \"prefix*\")  true             ; match topics by prefix
                            (evt-url \"sub-only\") true             ; implicitly allowed
                            (evt-url \"pub-only\") false            ; subscription is denied
                            :on-after            on-after-subscribe-fn};

           :on-publish     {(evt-url \"chat\")     on-publish-fn   ; custom event broker
                            (evt-url \"prefix*\")  true            ; pass events through as-is
                            (evt-url \"sub-only\") false           ; publishing is denied
                            (evt-url \"pub-only\") true
                            :on-after            on-after-publish-fn}

           :on-unsubscribe on-unsubscribe-fn})))

  Callback signatures:

    (on-open-fn sess-id)
    (on-close-fn sess-id status)

    (auth-secret-fn sess-id auth-key auth-extra)
      Provide the authentication secret for the key (ie. username) and
      (optionally) extra information from the client. Return nil if the key
      does not exist.

    (auth-permissions-fn sess-id auth-key)
      Returns a map of permissions the session is granted when the authentication
      succeeds for the given key.

      The permission map should be comprised of the topics that are allowed
      for each category:

        {:rpc       {\"http://example/rpc#call\"    true}
         :subscribe {\"http://example/event#allow\" true
                     \"http://example/event#deny\"  false}
         :publish   {\"http://example/event#allow\" true}}

      ...or you can allow all category topics:

        {:all true}

      ...or allow/deny all topics within a category:

        {:rpc       true
         :subscribe false
         :publish   true}

    (rpc-call ...)
      Can have any signature. The parameters received from the client will be applied as-is.
      The client session is also available in the bound *call-sess-id* var.
      The function may return a value as is, or in a result map: {:result \"my result\"},
      or as an error map: {:error {:uri \"http://example.com/error#give-error\"
                                   :message \"Test error\"
                                   :description \"Test error description\"
                                   :kill false}} ; true will close the connection after send

    (on-before-call-fn sess-id topic call-id call-params)
      To allow call, return params as vector: [sess-id topic call-id call-params]
      To deny, return nil/false.

    (on-after-call-error-fn sess-id topic call-id error)
      Return params as vector: [sess-id topic call-id error]

    (on-after-call-success-fn sess-id topic call-id result)
      Return params as vector: [sess-id topic call-id result]

    (on-subscribe-fn? sess-id topic)
      Return true to allow client to subscribe, false to deny.

    (on-after-subscribe-fn sess-id topic)
      No return values required.

    (on-publish-fn sess-id topic event exclude eligible)
      To allow publish, return params as vector: [sess-id topic event exclude eligible]
      To deny, return nil/false.

    (on-after-publish-fn sess-id topic event exclude eligible)
      No return values required.

    (on-unsubscribe-fn sess-id topic)
      No return values required."
  [channel callbacks-map]
  (let [callbacks-map (init-cr-auth callbacks-map)
        cb-on-open    (callbacks-map :on-open)
        sess-id       (core/add-client channel)
        instance      (create-instance sess-id callbacks-map)]

    (httpkit/on-close channel   (on-close sess-id
                                  (callbacks-map :on-close)
                                  (callbacks-map :on-unsubscribe)))
    (httpkit/on-receive channel (handle-json-message instance))
    (when (fn? cb-on-open) (cb-on-open sess-id))
    (init-auth-timer callbacks-map sess-id)
    sess-id))



(defn origin-match?
  "Compares a regular expression against the Origin: header.
  Used to help protect against CSRF, but do not depend on just
  this check. Best to use a server-generated CSRF token for comparison."
  [origin-re req]
  (if-let [req-origin (get-in req [:headers "origin"])]
    (re-matches origin-re req-origin)))

(defn subprotocol?
  "Checks if a protocol string exists in the Sec-WebSocket-Protocol
  list header."
  [proto req]
  (if-let [protocols (get-in req [:headers "sec-websocket-protocol"])]
    (some #{proto}
      (map #(lower-case (trim %))
        (split protocols #",")))))


(defmacro with-channel-validation
  "Replaces the HTTP Kit `with-channel` macro. Does extra validation
  to match the wamp subprotocol and origin URL matching.

  Example usage:

    (defn my-wamp-handler [request]
      (wamp/with-channel-validation request channel #\"https?://myhost\"
        (wamp/http-kit-handler channel { ... })))

  See org.httpkit.server for more information."
  [request ch-name origin-re & body]
  `(let [~ch-name (:async-channel ~request)]
     (if (:websocket? ~request)
       (if-let [key# (get-in ~request [:headers "sec-websocket-key"])]
         (if (origin-match? ~origin-re ~request)
           (if (subprotocol? wamp2/subprotocol-id ~request)
             (do
               (.sendHandshake ~(with-meta ch-name {:tag `AsyncChannel})
                 {"Upgrade"                "websocket"
                  "Connection"             "Upgrade"
                  "Sec-WebSocket-Accept"   (httpkit/accept key#)
                  "Sec-WebSocket-Protocol" wamp2/subprotocol-id}
                  )
               ~@body
               {:body ~ch-name})
             (do
              (println "\nLLLOOOGGG" "subprotocol mismatch"
                (get-in ~request [:headers "sec-websocket-protocol"]))
              {:status 400 :body "missing or bad WebSocket-Protocol"}))
           {:status 400 :body "missing or bad WebSocket-Origin"})
         {:status 400 :body "missing or bad WebSocket-Key"})
       {:status 400 :body "not websocket protocol"})))

