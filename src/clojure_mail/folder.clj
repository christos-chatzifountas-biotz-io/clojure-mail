(ns clojure-mail.folder
  (:refer-clojure :exclude [list])
  (:import [javax.mail.search SearchTerm OrTerm SubjectTerm BodyTerm RecipientStringTerm FromStringTerm FlagTerm]
           (com.sun.mail.imap IMAPFolder IMAPFolder$FetchProfileItem IMAPMessage)
           (javax.mail FetchProfile FetchProfile$Item)))

;; note that the get folder fn is part of the store namespace

(def ^:dynamic current-folder)

(defmacro with-folder [folder store & body]
  `(let [fd# (doto (.getFolder ~store ~folder) (.open IMAPFolder/READ_ONLY))]
     (binding [current-folder fd#]
       (do ~@body))))

(defn get-folder
  "Returns an IMAPFolder instance"
  [store folder-name]
  (.getFolder store folder-name))

(defn full-name [f]
  (.getFullName f))

(defn new-message-count
  "Get number of new messages in folder f"
  [f]
  (.getNewMessageCount f))

(defn message-count
  "Get total number of messages in folder f"
  [f]
  (.getMessageCount f))

(defn unread-message-count
  "Get number of unread messages in folder f"
  [f]
  (.getUnreadMessageCount f))

(defn get-message-by-uid [f id]
  (.getMessageByUID f id))

(defn get-message [f id]
  (.getMessage f id))

(defn fetch-messages
  "Pre-fetch message attributes for a given fetch profile.
  Messages are retrieved as light weight objects and individual fields such as headers or body are populated lazily.
  When bulk fetching messages you can pre-fetch these items based on a com.sun.mail.imap.FetchProfileItem
  f - the folder from which to fetch the messages
  ms - the messages to fetch
  :fetch-profile - optional fetch profile, defaults to entire message. fetch profiles are:

      :message
      :headers
      :flags
      :envelope
      :content-info
      :size
      "
  [f ms & {:keys [fetch-profile] :or {fetch-profile :message}}]
  (let [fp (FetchProfile.)
        item (condp = fetch-profile
               :message IMAPFolder$FetchProfileItem/MESSAGE
               :headers IMAPFolder$FetchProfileItem/HEADERS
               :flags IMAPFolder$FetchProfileItem/FLAGS
               :envelope IMAPFolder$FetchProfileItem/ENVELOPE
               :content-info IMAPFolder$FetchProfileItem/CONTENT_INFO
               :size FetchProfile$Item/SIZE)
        _ (.add fp item)]
    (.fetch f (into-array IMAPMessage ms) fp)))

(defn get-messages
  "Gets all messages from folder f or get the Message objects for message numbers ranging from start through end,
  both start and end inclusive. Note that message numbers start at 1, not 0."
  ([folder]
   (.getMessages folder))
  ([folder start end]
   (.getMessages folder start end)))

(defn to-recipient-type 
  [rt]
  (cond 
    :to javax.mail.Message$RecipientType/TO
    :cc javax.mail.Message$RecipientType/CC
    :bcc javax.mail.Message$RecipientType/BCC))

(defn to-flag
  [fl]
  (cond 
    (:-answered? :answered?) javax.mail.Flags$Flag/ANSWERED
    (:-deleted? :deleted?) javax.mail.Flags$Flag/DELETED
    (:flagged? :flagged) javax.mail.Flags$Flag/FLAGGED
    (:-draft? :draft?) javax.mail.Flags$Flag/DRAFT
    (:-recent? :recent?) javax.mail.Flags$Flag/RECENT
    (:-seen? :seen?.) javax.mail.Flags$Flag/SEEN))

(defn build-search-terms
  "This creates a search condition. Input is a sequence of message part conditions or flags or header conditions.
   Possible message part condititon is: (:from|:cc|:bcc|:to|:subject|:body) value or date condition.
   Date condition is: (:received|:sent) (:after|:before|:on) date
   Header condition is: :header (header-name-string header-value, ...)
   Supported flags are: :answered?, :deleted?, :draft?, :recent?, :flagged? :seen?. Minus sign at the beginning of flag tests for negated flag value (ex. :-answered? not answered messages).

   Terms on the same level is connected with and-ed, if value is a sequence, then those values are or-ed. 
    
   Examples: 
    (:body \"foo\" :body \"are\") - body should match both values.
    (:body [\"foo\" \"are\"]) - body should match one of the values.
    (:body \"foo\" :from \"john@exmaple.com\") - body should match foo and email is sent by john."
  [query]
    (let [ft (first query)
          inst (fn [a & params] (eval `(new ~a ~@params)))
          or-term-builder (fn[cl params]
                            (if (coll? params) 
                              (OrTerm. (into-array (map #(inst cl %) params)))
                              (inst cl params)))]
      (case ft
        :body (or-term-builder BodyTerm (second query))
        :from (or-term-builder FromStringTerm (second query))
        (:to :cc :bcc) (RecipientStringTerm. (to-recipient-type ft) (second query))
        (:answered? :deleted? :draft? :recent? :seen? :flagged?) (FlagTerm. (to-flag ft) true)
        (:-answered? :-deleted? :-draft? :-recent? :-seen? :-flagged?) (FlagTerm. (to-flag ft) false)
        :subject (or-term-builder SubjectTerm (second query)))))

(defn search [f & query]
  (let [search-term (if (string? (first query))
                      (OrTerm. (SubjectTerm. (first query)) (BodyTerm. (first query)))
                      (build-search-terms query))]
    (.search f search-term)))

(defn list
  "List all folders under folder f"
  [f]
  (.list f))
