package com.yegorkapomidorka.headswap.dongle;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** После загрузки поднимаем сервис: он держит Wi-Fi без энергосбережения и подхватит уже воткнутый аксессуар. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        context.startService(new Intent(context, RelayService.class));
    }
}
