package com.yegorkapomidorka.headswap.dongle;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Ловит USB_ACCESSORY_ATTACHED (на 4.1 его получает только активити) и сразу отдаёт сервису. */
public class AttachActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent svc = new Intent(this, RelayService.class);
        Intent src = getIntent();
        if (src != null && src.getExtras() != null) svc.putExtras(src.getExtras());
        startService(svc);
        finish();
    }
}
