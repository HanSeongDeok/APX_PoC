package com.suresofttech.apx.ui.widget;

import java.io.ByteArrayOutputStream;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;

/**
 * SWT Control → PNG 바이트. 측정 증거 스냅샷용.
 */
public final class SwtCapture {

    private SwtCapture() {
    }

    /** 컨트롤 클라이언트 영역을 PNG로 캡처. dispose/비표시면 null. */
    public static byte[] toPng(Control control) {
        if (control == null || control.isDisposed()) {
            return null;
        }
        Rectangle bounds = control.getBounds();
        if (bounds.width <= 0 || bounds.height <= 0) {
            return null;
        }
        // 대기 중인 paint를 먼저 반영(redraw 직후 캡처 시 빈 화면 방지)
        control.update();
        Image image = new Image(control.getDisplay(), bounds.width, bounds.height);
        GC gc = new GC(control);
        try {
            gc.copyArea(image, 0, 0);
        } finally {
            gc.dispose();
        }
        try {
            ImageLoader loader = new ImageLoader();
            loader.data = new ImageData[] { image.getImageData() };
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            loader.save(bos, SWT.IMAGE_PNG);
            return bos.toByteArray();
        } finally {
            image.dispose();
        }
    }
}
