package com.yegorkapomidorka.headswap.aa

/**
 * Куда уходят подсказки навигатора рабочего стола (свой хост Car App, теневая AA): мост рабочего стола (AaBridgeCore),
 * дальше штатный MetaRelay -> NAVI_NEXT_TURN_INFO на ГУ. Ставит BridgeService на время сеанса рабочего стола.
 */
object DesktopNavHints {
    @Volatile var target: AaMetaSink? = null
}
