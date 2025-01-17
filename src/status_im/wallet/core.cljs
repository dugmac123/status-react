(ns status-im.wallet.core
  (:require [clojure.set :as set]
            [re-frame.core :as re-frame]
            [status-im.multiaccounts.update.core :as multiaccounts.update]
            [status-im.constants :as constants]
            [status-im.ethereum.core :as ethereum]
            [status-im.ethereum.json-rpc :as json-rpc]
            [status-im.ethereum.tokens :as tokens]
            [status-im.i18n :as i18n]
            [status-im.ui.screens.navigation :as navigation]
            [status-im.wallet.utils :as wallet.utils]
            [status-im.utils.config :as config]
            [status-im.utils.core :as utils.core]
            [status-im.utils.fx :as fx]
            [status-im.utils.money :as money]
            [status-im.utils.prices :as prices]
            [status-im.utils.utils :as utils.utils]
            [taoensso.timbre :as log]
            [status-im.wallet.db :as wallet.db]
            [status-im.ethereum.abi-spec :as abi-spec]
            [status-im.signing.core :as signing]))

(re-frame/reg-fx
 :wallet/get-balance
 (fn [{:keys [account-address on-success on-error]}]
   (json-rpc/call
    {:method     "eth_getBalance"
     :params     [account-address "latest"]
     :on-success on-success
     :on-error   on-error})))

(re-frame/reg-fx
 :wallet/get-balances
 (fn [addresses]
   (doseq [address addresses]
     (json-rpc/call
      {:method     "eth_getBalance"
       :params     [address "latest"]
       :on-success #(re-frame/dispatch [:wallet.callback/update-balance-success address %])
       :on-error    #(re-frame/dispatch [:wallet.callback/update-balance-fail %])}))))

;; TODO(oskarth): At some point we want to get list of relevant
;; assets to get prices for
(re-frame/reg-fx
 :wallet/get-prices
 (fn [{:keys [from to mainnet? success-event error-event chaos-mode?]}]
   (prices/get-prices from
                      to
                      mainnet?
                      #(re-frame/dispatch [success-event %])
                      #(re-frame/dispatch [error-event %])
                      chaos-mode?)))

(defn assoc-error-message [db error-type err]
  (assoc-in db [:wallet :errors error-type] (or err :unknown-error)))

(fx/defn on-update-prices-fail
  [{:keys [db]} err]
  (log/debug "Unable to get prices: " err)
  {:db (-> db
           (assoc-error-message :prices-update :error-unable-to-get-prices)
           (assoc :prices-loading? false))})

(fx/defn on-update-balance-fail
  [{:keys [db]} err]
  (log/debug "Unable to get balance: " err)
  {:db (-> db
           (assoc-error-message :balance-update :error-unable-to-get-balance))})

(fx/defn on-update-token-balance-fail
  [{:keys [db]} symbol err]
  (log/debug "Unable to get token " symbol "balance: " err)
  {:db (-> db
           (assoc-error-message :balance-update :error-unable-to-get-token-balance))})

(fx/defn open-transaction-details
  [{:keys [db] :as cofx} hash address]
  (navigation/navigate-to-cofx cofx :wallet-transaction-details {:hash hash :address address}))

(defn- validate-token-name!
  [{:keys [address symbol name]}]
  (json-rpc/eth-call
   {:contract address
    :method "name()"
    :outputs ["string"]
    :on-success
    (fn [[contract-name]]
      (when (and (not (empty? contract-name))
                 (not= name contract-name))
        (let [message (i18n/label :t/token-auto-validate-name-error
                                  {:symbol   symbol
                                   :expected name
                                   :actual   contract-name
                                   :address  address})]
          (log/warn message)
          (utils.utils/show-popup (i18n/label :t/warning) message))))}))

(defn- validate-token-symbol!
  [{:keys [address symbol]}]
  (json-rpc/eth-call
   {:contract address
    :method "symbol()"
    :outputs ["string"]
    :on-success
    (fn [[contract-symbol]]
      ;;NOTE(goranjovic): skipping check if field not set in contract
      (when (and (not (empty? contract-symbol))
                 (not= (clojure.core/name symbol) contract-symbol))
        (let [message (i18n/label :t/token-auto-validate-symbol-error
                                  {:symbol   symbol
                                   :expected (clojure.core/name symbol)
                                   :actual   contract-symbol
                                   :address  address})]
          (log/warn message)
          (utils.utils/show-popup (i18n/label :t/warning) message))))}))

(defn- validate-token-decimals!
  [{:keys [address symbol decimals nft?]}]
  (when-not nft?
    (json-rpc/eth-call
     {:contract address
      :method "decimals()"
      :outputs ["uint256"]
      :on-success
      (fn [[contract-decimals]]
        (when (and (not (nil? contract-decimals))
                   (not= decimals contract-decimals))
          (let [message (i18n/label :t/token-auto-validate-decimals-error
                                    {:symbol   symbol
                                     :expected decimals
                                     :actual   contract-decimals
                                     :address  address})]
            (log/warn message)
            (utils.utils/show-popup (i18n/label :t/warning) message))))})))

(re-frame/reg-fx
 :wallet/validate-tokens
 (fn [tokens]
   (doseq [token tokens]
     (validate-token-decimals! token)
     (validate-token-symbol! token)
     (validate-token-name! token))))

(re-frame/reg-fx
 :wallet/get-tokens-balances
 (fn [{:keys [addresses tokens assets]}]
   ;;TODO not great to have so many calls , should be optimized, there is wallet_getTokensBalances why wouldn't use it?
   (doseq [{:keys [address symbol]} tokens]
     (doseq [account-address addresses]
       (json-rpc/eth-call
        {:contract   address
         :method     "balanceOf(address)"
         :params     [account-address]
         :outputs    ["uint256"]
         :on-success (fn [[balance]]
                       (if (and assets (assets symbol))
                         (re-frame/dispatch [:wallet.callback/update-token-balance-success account-address symbol balance])
                         ;; NOTE: when there it is not a visible assets we make an initialization round
                         (when (pos? balance)
                           (re-frame/dispatch [:wallet/token-found account-address symbol balance]))))
         :on-error   #(re-frame/dispatch [:wallet.callback/update-token-balance-fail symbol %])})))))

(defn clear-error-message [db error-type]
  (update-in db [:wallet :errors] dissoc error-type))

(defn tokens-symbols
  [visible-token-symbols all-tokens chain]
  (set/difference (set visible-token-symbols)
                  (set (map :symbol (tokens/nfts-for all-tokens chain)))))

(fx/defn initialize-tokens
  [{:keys [db] :as cofx}]
  (let [custom-tokens (get-in db [:multiaccount :settings :wallet :custom-tokens])
        chain         (ethereum/chain-keyword db)
        ;;TODO why do we need all tokens ? chain can be changed only through relogin
        all-tokens    (merge-with
                       merge
                       (utils.core/map-values #(utils.core/index-by :address %)
                                              tokens/all-default-tokens)
                       custom-tokens)]
    (fx/merge
     cofx
     (merge
      {:db (assoc db :wallet/all-tokens all-tokens)}
      (when config/erc20-contract-warnings-enabled?
        {:wallet/validate-tokens (get tokens/all-default-tokens chain)})))))

(fx/defn update-balances
  [{{:keys                       [network-status :wallet/all-tokens]
     {:keys [settings accounts]} :multiaccount :as db} :db :as cofx} addresses]
  (let [addresses (or addresses (map :address accounts))
        chain     (ethereum/chain-keyword db)
        assets    (get-in settings [:wallet :visible-tokens chain])
        tokens    (->> (tokens/tokens-for all-tokens chain)
                       (remove #(or (:hidden? %))))]
    (when (not= network-status :offline)
      (fx/merge
       cofx
       {:wallet/get-balances        addresses
        :wallet/get-tokens-balances {:addresses addresses
                                     :assets    assets
                                     :tokens    tokens}
        :db                         (-> db
                                        (clear-error-message :balance-update))}
       (when-not assets
         (multiaccounts.update/update-settings
          (assoc-in settings
                    [:wallet :visible-tokens chain]
                    #{})
          {}))))))

(fx/defn update-prices
  [{{:keys [network-status :wallet/all-tokens]
     {:keys [address chaos-mode? settings]} :multiaccount :as db} :db}]
  (let [chain       (ethereum/chain-keyword db)
        mainnet?    (= :mainnet chain)
        assets      (get-in settings [:wallet :visible-tokens chain])
        tokens      (tokens-symbols assets all-tokens chain)
        currency-id (or (get-in settings [:wallet :currency]) :usd)
        currency    (get constants/currencies currency-id)]
    (when (not= network-status :offline)
      {:wallet/get-prices
       {:from          (if mainnet?
                         (conj tokens "ETH")
                         [(-> (tokens/native-currency chain)
                              (wallet.utils/exchange-symbol))])
        :to            [(:code currency)]
        :mainnet?      mainnet?
        :success-event :wallet.callback/update-prices-success
        :error-event   :wallet.callback/update-prices-fail
        :chaos-mode?   chaos-mode?}

       :db
       (-> db
           (clear-error-message :prices-update)
           (assoc :prices-loading? true))})))

(defn- set-checked [ids id checked?]
  (if checked?
    (conj (or ids #{}) id)
    (disj ids id)))

(fx/defn on-update-prices-success
  [{:keys [db]} prices]
  {:db (assoc db
              :prices prices
              :prices-loading? false)})

(fx/defn update-balance
  [{:keys [db]} address balance]
  {:db (-> db
           (assoc-in [:wallet :accounts address :balance :ETH] (money/bignumber balance)))})

(fx/defn update-token-balance
  [{:keys [db]} address symbol balance]
  {:db (-> db
           (assoc-in [:wallet :accounts address :balance symbol] (money/bignumber balance)))})

(defn update-toggle-in-settings
  [{{:keys [multiaccount] :as db} :db} symbol checked?]
  (let [chain        (ethereum/chain-keyword db)
        settings     (get multiaccount :settings)]
    (update-in settings [:wallet :visible-tokens chain] #(set-checked % symbol checked?))))

(fx/defn toggle-visible-token
  [cofx symbol checked?]
  (let [new-settings (update-toggle-in-settings cofx symbol checked?)]
    (multiaccounts.update/update-settings cofx new-settings {})))

(fx/defn add-custom-token
  [{:keys [db] :as cofx} {:keys [symbol address] :as token}]
  (let [chain        (ethereum/chain-keyword db)
        settings     (update-toggle-in-settings cofx symbol true)
        new-settings (assoc-in settings [:wallet :custom-tokens chain address] token)]
    (multiaccounts.update/update-settings cofx new-settings {})))

(fx/defn remove-custom-token
  [{:keys [db] :as cofx} {:keys [symbol address]}]
  (let [chain        (ethereum/chain-keyword db)
        settings     (update-toggle-in-settings cofx symbol false)
        new-settings (update-in settings [:wallet :custom-tokens chain] dissoc address)]
    (multiaccounts.update/update-settings cofx new-settings {})))

(fx/defn configure-token-balance-and-visibility
  [cofx address symbol balance]
  (fx/merge cofx
            (toggle-visible-token symbol true)
            ;;TODO(goranjovic): move `update-token-balance-success` function to wallet models
            (update-token-balance address symbol balance)))

(defn set-and-validate-amount-db [db amount symbol decimals]
  (let [{:keys [value error]} (wallet.db/parse-amount amount decimals)]
    (-> db
        (assoc-in [:wallet :send-transaction :amount] (money/formatted->internal value symbol decimals))
        (assoc-in [:wallet :send-transaction :amount-text] amount)
        (assoc-in [:wallet :send-transaction :amount-error] error))))

(fx/defn set-and-validate-amount
  {:events [:wallet.send/set-and-validate-amount]}
  [{:keys [db]} amount symbol decimals]
  {:db (set-and-validate-amount-db db amount symbol decimals)})

(fx/defn set-symbol
  {:events [:wallet.send/set-symbol]}
  [{:keys [db]} symbol]
  {:db (-> db
           (assoc-in [:wallet :send-transaction :symbol] symbol)
           (assoc-in [:wallet :send-transaction :amount] nil)
           (assoc-in [:wallet :send-transaction :amount-text] nil)
           (assoc-in [:wallet :send-transaction :asset-error] nil))})

(fx/defn sign-transaction-button-clicked
  {:events  [:wallet.ui/sign-transaction-button-clicked]}
  [{:keys [db] :as cofx}]
  (let [{:keys [to symbol amount from]} (get-in cofx [:db :wallet :send-transaction])
        {:keys [symbol address]} (tokens/asset-for (:wallet/all-tokens db)
                                                   (ethereum/chain-keyword db)
                                                   symbol)
        amount-hex (str "0x" (abi-spec/number-to-hex amount))
        to-norm (ethereum/normalized-address to)]
    (signing/sign cofx {:tx-obj    (if (= symbol :ETH)
                                     {:to   to-norm
                                      :from from
                                      :value amount-hex}
                                     {:to   (ethereum/normalized-address address)
                                      :from from
                                      :data (abi-spec/encode "transfer(address,uint256)" [to-norm amount-hex])})
                        :on-result [:navigate-back]})))

(fx/defn set-and-validate-amount-request
  {:events [:wallet.request/set-and-validate-amount]}
  [{:keys [db]} amount symbol decimals]
  (let [{:keys [value error]} (wallet.db/parse-amount amount decimals)]
    {:db (-> db
             (assoc-in [:wallet :request-transaction :amount] (money/formatted->internal value symbol decimals))
             (assoc-in [:wallet :request-transaction :amount-text] amount)
             (assoc-in [:wallet :request-transaction :amount-error] error))}))

(fx/defn set-symbol-request
  {:events [:wallet.request/set-symbol]}
  [{:keys [db]} symbol]
  {:db (-> db
           (assoc-in [:wallet :request-transaction :symbol] symbol))})
