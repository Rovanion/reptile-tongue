(ns reptile.tongue.views
  (:require [re-frame.core :as re-frame]
            [re-com.core :refer [h-box v-box box button gap line scroller border label input-text md-circle-icon-button
                                 md-icon-button input-textarea modal-panel h-split v-split title flex-child-style
                                 radio-button p]]
            [re-com.splits :refer [hv-split-args-desc]]
            [reptile.tongue.events :as events]
            [reptile.tongue.subs :as subs]
            [cljs.reader :as edn]
            [reagent.core :as reagent]
            [parinfer-cljs.core :as parinfer]
            [clojure.string :as str]
            [cljs.reader :as rdr]
            [cljs.tools.reader.reader-types :as treader-types]))

(def default-style {:font-family "Menlo, Lucida Console, Monaco, monospace"
                    :border      "1px solid lightgray"
                    :padding     "5px 5px 5px 5px"})

(def readonly-panel-style (merge (flex-child-style "1")
                                 default-style
                                 {:background-color "rgba(171, 171, 129, 0.1)"
                                  :color            "gray"
                                  :resize           "none"}))

(def other-editor-style (merge (flex-child-style "1")
                               default-style
                               {:font-size        "10px"
                                :color            "gray"
                                :font-weight      "lighter"
                                :padding          "3px 3px 3px 3px"
                                :background-color "rgba(171, 171, 129, 0.1)"}))

(def input-style (merge (flex-child-style "1")
                        default-style
                        {:color       "black"
                         :font-weight "bolder"
                         :resize      "none"}))

(def eval-panel-style (merge (flex-child-style "1")
                             default-style))

(def eval-content-style (merge (flex-child-style "1")
                               {:resize    "none"
                                :flex-flow "column-reverse nowrap"
                                :padding   "3px 3px 3px 3px"}))

(def history-style {:padding "5px 5px 0px 10px"})

(def status-style (merge (dissoc default-style :border)
                         {:font-size   "10px"
                          :font-weight "lighter"
                          :color       "lightgrey"}))

; TODO use background image
; :background-image "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' version='1.1' height='50px' width='120px'><text x='0' y='15' fill='red' font-size='20'>I love SVG!</text></svg>"

(defn read-input-panel
  [user-name]
  (let [current-form @(re-frame/subscribe [::subs/user-keystrokes (keyword user-name)])]
    [h-box :size "auto" :children
     [[:textarea {:id          (str "read-input-panel-" user-name)
                  :placeholder user-name
                  :style       readonly-panel-style
                  :disabled    true
                  :on-change   #(-> nil)
                  :value       (when current-form
                                 (:text (parinfer/paren-mode current-form)))}]]]))

(defn format-trace
  [via trace]
  (let [show-trace? (reagent/atom false)]
    (fn
      []
      [v-box :children
       [[md-icon-button :md-icon-name "zmdi-zoom-in" :tooltip (first via) :on-click #(reset! show-trace? true)]
        (when @show-trace?
          [modal-panel :backdrop-on-click #(reset! show-trace? false)
           :child
           [scroller :height "85vh"
            :child
            [v-box :size "auto"
             :children
             [[label :label (:type (first via))]
              (map (fn [element] [label :label (str element)]) trace)
              [gap :size "20px"]
              [button :label "Done (or click the backdrop)" :on-click #(reset! show-trace? false)]]]]])]])))

(defn format-exception
  [val form]
  (let [{:keys [cause via trace]} (edn/read-string val)]
    (println "cause" cause "via" (:type (first via)))
    [v-box :size "auto"
     :children [[label :label form]
                [h-box :size "auto"
                 :children [[label :style {:color "red"} :label (str "=> " (or cause (:type (first via)) "??"))]
                            [gap :size "20px"]
                            [format-trace via trace]]]
                [gap :size "20px"]]]))

; Bug - double quotes dropped in favour of single quotes
(defn prettify
  "Hack to remove HTML entities from hiccup generated by the server."
  [eval-result]
  (edn/read-string
    (let [entities [[#"&nbsp;" ""]
                    [#"&quot;" "'"]
                    [#"&lt;" "<"]
                    [#"&gt;" ">"]
                    [#"&amp;" "&"]
                    [#"&apos;" "'"]]]
      (loop [regexes entities
             result  eval-result]
        (let [[find replace] (first regexes)]
          (if (empty? regexes)
            result
            (recur (rest regexes)
                   (clojure.string/replace result find replace))))))))

(defn format-result
  [result]
  (let [eval-result  (:prepl-response result)
        pretty-val   (prettify (:highlighted-form result))
        source       (:source result)
        returned-val (:val (last eval-result))
        val          (try (edn/read-string returned-val)
                          (catch js/Error e e))]
    ;(when-let [spec-fail-data (:data (last eval-result))]
    ;  (println "spec-fail:" spec-fail-data))
    (if (and (map? val) (= #{:cause :via :trace} (set (keys val))))
      [format-exception returned-val pretty-val]
      [v-box :size "auto" :children
       [[h-box :align :center :gap "20px"
         :children [[label :label pretty-val]
                    (when (= source :system)
                      [label :style {:color :lightgray :font-weight :lighter}
                       :label (str "(invoked by " (name source) ")")])]]
        (map (fn [printable]
               [label :label (:val printable)])
             (drop-last eval-result))
        [label :label (str "=> " (:val (last eval-result)))] ; Properly format the output
        [gap :size "20px"]]])))

(defn eval-panel
  "We need to use a lower level reagent function to have the content scroll from the bottom"
  []
  (reagent/create-class
    {:component-did-update
     (fn event-expression-component-did-update [this]
       (let [node (reagent/dom-node this)]
         (set! (.-scrollTop node) (.-scrollHeight node))))

     :display-name
     "eval-component"

     :reagent-render
     (fn
       []
       (let [eval-results @(re-frame/subscribe [::subs/eval-results])]
         [scroller :style eval-panel-style
          :child
          [box :style eval-content-style :size "auto"
           :child
           [:div (when eval-results
                   [v-box :size "auto" :children
                    (map format-result eval-results)])]]]))}))

(defn edit-component
  [panel-name]
  (let [pre-cursor (atom {:cursor-x 0 :cursor-line 0 :text ""})
        new-cursor (atom {:cursor-x 0 :cursor-line 0 :text ""})]
    (reagent/create-class
      {:component-did-mount
       (fn event-expression-component-did-mount [this]
         (.focus (reagent/dom-node this)))

       :component-did-update
       (fn event-expression-component-did-update [this]
         (let [node   (reagent/dom-node this)
               pre-x  (:cursor-x @pre-cursor)
               {:keys [cursor-x text cursor-line]} @new-cursor
               offset (+ cursor-x cursor-line (reduce + (map #(-> text
                                                                  str/split-lines
                                                                  (nth %)
                                                                  count)
                                                             (range cursor-line))))]
           ;; Set the focus on the text area
           (.focus node)

           ;; Move the cursor
           (when (not= pre-x cursor-x)
             (reset! pre-cursor @new-cursor)
             (.setSelectionRange node offset offset))))

       :display-name
       "edit-component"

       :reagent-render
       (fn
         [panel-name]
         (let [parinfer-form   @(re-frame/subscribe [::subs/parinfer-form])
               parinfer-cursor @(re-frame/subscribe [::subs/parinfer-cursor])
               key-down        @(re-frame/subscribe [::subs/key-down])
               network-status  @(re-frame/subscribe [::subs/network-status])
               text-style      (if network-status
                                 input-style
                                 (assoc input-style :font-weight :lighter :color :lightgray))]
           (reset! new-cursor parinfer-cursor)
           [:textarea
            {:id            panel-name
             :auto-complete false
             :placeholder   (str "(your clojure here) - " panel-name)
             :style         text-style

             ;; Dispatch on Alt-Enter
             :on-key-down   #(re-frame/dispatch [::events/key-down (.-which %)])
             :on-key-up     #(re-frame/dispatch [::events/key-up (.-which %)])
             :on-key-press  #(do (re-frame/dispatch [::events/key-press (.-which %)])
                                 (when (and (= (.-which %) 13) (= key-down 18))
                                   (re-frame/dispatch [::events/eval (-> % .-currentTarget .-value)])))

             :on-change     #(let [current-value   (-> % .-currentTarget .-value)
                                   selection-start (-> % .-currentTarget .-selectionStart)
                                   cursor-line     (-> (subs current-value 0 selection-start)
                                                       str/split-lines count dec)
                                   cursor-pos      (-> (subs current-value 0 selection-start)
                                                       str/split-lines last count)]
                               (re-frame/dispatch [::events/current-form current-value cursor-line cursor-pos
                                                   (:cursor-line @pre-cursor)
                                                   (:cursor-x @pre-cursor)]))
             :value         parinfer-form}]))})))

(defn format-history-item
  [historical-form]
  [md-icon-button :md-icon-name "zmdi-comment-text" :tooltip historical-form
   :on-click #(re-frame/dispatch [::events/current-form historical-form])])

(defn visual-history
  []
  (let [eval-results (re-frame/subscribe [::subs/eval-results])]
    (fn
      []
      (when @eval-results
        [h-box :size "auto" :align :center :style history-style :children
         (reverse (map format-history-item (distinct (map :form @eval-results))))]))))


(defn lib-type
  [lib-data]
  [v-box :gap "20px"
   :children [(doall (for [maven? [:maven :git]]            ;; Notice the ugly "doall"
                       ^{:key maven?}                       ;; key should be unique among siblings
                       [radio-button
                        :label (name maven?)
                        :value maven?
                        :model (if (:maven @lib-data) :maven :git)
                        :on-change #(swap! lib-data assoc :maven (= :maven %))]))]])

(defn dep-name
  [lib-data]
  [v-box :gap "10px" :children
   [[label :label "Dependency Name"]
    [input-text
     :width "350px"
     :model (:name @lib-data)
     :on-change #(swap! lib-data assoc :name %)]]])

; TODO - add :classifier, :extension, :exclusions options
(defn maven-dep
  [lib-data]
  [v-box :gap "10px" :children
   [[label :label "Maven Version"]
    [input-text
     :width "350px"
     :model (:version @lib-data)
     :on-change #(swap! lib-data assoc :version %)]]])

; TODO - add :tag option
(defn git-dep
  [lib-data]
  [v-box :gap "10px" :children
   [[label :label "Repository URL"]
    [input-text
     :width "350px"
     :model (:url @lib-data)
     :on-change #(swap! lib-data assoc :url %)]
    [label :label "Commit SHA"]
    [input-text
     :width "350px"
     :model (:sha @lib-data)
     :on-change #(swap! lib-data assoc :sha %)]]])

(defn add-lib-form
  [lib-data process-ok]
  (fn []
    [border
     :border "1px solid #eee"
     :child [v-box
             :gap "30px" :padding "10px"
             :height "450px"
             :children
             [[title :label "Add a dependency to the REPL" :level :level2]
              [v-box
               :gap "10px"

               ;; TODO enable GIT | Maven | Disk lib specs

               :children [[lib-type lib-data]
                          [dep-name lib-data]               ; cond
                          (if (:maven @lib-data)
                            [maven-dep lib-data]
                            [git-dep lib-data])
                          [gap :size "30px"]
                          [button :label "Add" :on-click process-ok]]]]]]))

;; Refactor out the add-lib modal

(defn edit-panel
  [panel-name]
  (let [show-add-lib? (reagent/atom false)
        lib-data      (reagent/atom {:name    "clojurewerkz/money"
                                     :version "1.10.0"
                                     :url     "https://github/iguana"
                                     :sha     "888abcd888888b5cba88882b8888bdf59f9d88b6"
                                     :maven   true})
        add-lib-event (fn []
                        (reset! show-add-lib? false)
                        (re-frame/dispatch [::events/add-lib @lib-data]))]
    (fn
      []
      [v-box :size "auto"
       :children
       [[box :size "auto" :child
         [scroller :child
          [edit-component panel-name]]]
        [gap :size "5px"]
        [h-box
         :align :center :children
         [[button
           :label "Eval (or Alt-Enter)"
           :on-click #(re-frame/dispatch [::events/eval
                                          (.-value (.getElementById js/document panel-name))])]
          [gap :size "30px"]
          [md-circle-icon-button :md-icon-name "zmdi-plus" :tooltip "Add a library" :size :smaller
           :on-click #(reset! show-add-lib? true)]
          (when @show-add-lib?
            [modal-panel
             :backdrop-color "lightgray"
             :backdrop-on-click #(reset! show-add-lib? false)
             :backdrop-opacity 0.7
             :child [add-lib-form lib-data add-lib-event]])
          [gap :size "20px"]
          [visual-history]]]]])))

(defn status-bar
  [user-name]
  (let [edit-status    @(re-frame/subscribe [::subs/status])
        edit-style     {:color (if edit-status "red" "rgba(127, 191, 63, 0.32)")}
        network-status @(re-frame/subscribe [::subs/network-status])
        network-style  {:color (if network-status "rgba(127, 191, 63, 0.32)" "red")}]
    [v-box :children
     [[line]
      [h-box :size "20px" :style status-style :gap "20px" :align :center :children
       [[label :label (str "User: " user-name)]
        [line]
        [label :style network-style :label "Connect Status:"]
        (if network-status
          [md-icon-button :md-icon-name "zmdi-cloud-done" :size :smaller :style network-style]
          [md-icon-button :md-icon-name "zmdi-cloud-off" :size :smaller :style network-style])
        [line]
        [label :style edit-style :label "Edit Status:"]
        [label :style edit-style :label (or edit-status "OK")]]]
      [line]]]))


(defn login-form
  [form-data process-ok]
  [border
   :border "1px solid #eee"
   :child [v-box
           :size "auto"
           :gap "30px" :padding "10px"
           :children [[title :label "Welcome to REPtiLe" :level :level2]
                      [v-box
                       :gap "10px"
                       :children [[label :label "User name"]
                                  [input-text
                                   :model (:user @form-data)
                                   :on-change #(swap! form-data assoc :user %)]
                                  [label :label "Shared secret"]
                                  [input-text
                                   :model (:secret @form-data)
                                   :on-change #(swap! form-data assoc :secret %)]
                                  [gap :size "30px"]
                                  [button :label "Access" :on-click process-ok]]]]]])

(defn login
  []
  (let [logged-in  @(re-frame/subscribe [::subs/logged-in])
        form-data  (reagent/atom {:user   "?"
                                  :secret "6738f275-513b-4ab9-8064-93957c4b3f35"})
        process-ok (fn [] (re-frame/dispatch [::events/login @form-data]))]
    (fn [] (when-not logged-in
             [modal-panel
              :backdrop-color "lightblue"
              :backdrop-opacity 0.1
              :child [login-form form-data process-ok]]))))

(defn read-panels
  [other-editors]
  (when other-editors
    [v-box :size "auto"
     :children (vec (map #(read-input-panel %) other-editors))]))

(defn main-panels
  [user-name other-editors]
  [v-box :style {:position "absolute"
                 :top      "18px"
                 :bottom   "0px"
                 :width    "100%"}
   :children
   [[h-split :splitter-size "2px" :initial-split "45%"
     :panel-1 (if (empty? other-editors)
                [edit-panel user-name]
                [v-split :initial-split "60%"
                 :panel-1 [read-panels other-editors]
                 :panel-2 [edit-panel user-name]])
     :panel-2 [eval-panel]]
    [gap :size "10px"]
    [status-bar user-name]]])

(defn main-panel
  []
  (let [user-name     @(re-frame/subscribe [::subs/user-name])
        other-editors @(re-frame/subscribe [::subs/other-editors user-name])]
    (if user-name
      [main-panels user-name other-editors]
      [login])))


