package com.suresofttech.apx.core.vision;

import java.awt.image.BufferedImage;

import com.suresofttech.apx.core.config.ApxSettings;

/**
 * 비전 판정기 <b>선택 지점</b> - 기본은 NCC, 클라이언트가 YOLO로 갈아끼울 수 있다.
 *
 * <p>설정 프리뷰({@code RoiNcc})와 측정 세션({@code MeasureSession})은 판정기를
 * 직접 {@code new} 하지 않고 여기서 받아간다. 그래서 아래 한 줄이면 전체가 바뀐다.
 *
 * <pre>
 * // 클라이언트(이솝) 시작 시 - YOLO 판정 사용
 * YoloVisionJudge.Cfg cfg = new YoloVisionJudge.Cfg();
 * cfg.modelPath = "C:/models/popup-cls.onnx";
 * cfg.inputSize = 224;      // 학습 imgsz 와 동일
 * cfg.hitClassId = 1;       // PASS 로 볼 클래스
 * VisionJudges.useYolo(cfg);
 *
 * VisionJudges.useNcc();    // 되돌리기(기본)
 * </pre>
 *
 * <p>모델을 못 읽으면 YOLO 판정기는 {@code state="no-model"} 만 돌려주므로,
 * 화면이 깨지지 않고 "판정 안 함" 상태로 남는다. 되돌리려면 {@link #useNcc()}.
 */
public final class VisionJudges {

    /** 판정기 생성 규칙. */
    public interface Factory {
        /**
         * @param refImage 기준 프레임(라이브 등록용). {@code refPath} 가 있으면 무시될 수 있다
         * @param refPath  기준 이미지 경로. null/빈문자면 {@code refImage} 사용
         * @param roi      비교 영역 {y1,y2,x1,x2}. null이면 구현 기본값(중앙)
         * @param thr      PASS 임계(0~1)
         */
        VisionJudge create(BufferedImage refImage, String refPath, int[] roi, double thr);
    }

    /** 기본 - 기준 영상과 픽셀을 비교하는 결정론적 NCC 판정기. */
    public static final Factory NCC_FACTORY = new Factory() {
        public VisionJudge create(BufferedImage refImage, String refPath, int[] roi, double thr) {
            if (refPath != null && !refPath.isEmpty()) {
                return new RoiMatchDetector(refPath, roi, thr);
            }
            return new RoiMatchDetector(refImage, roi, thr);
        }
    };

    private static volatile Factory factory = NCC_FACTORY;

    private VisionJudges() {
    }

    /** 현재 판정기 이름 - HUD / 증거 표기용("NCC"/"YOLO"). */
    public static String currentName() {
        Factory f = factory;
        return (f == NCC_FACTORY) ? "NCC" : "YOLO";
    }

    public static Factory getFactory() {
        return factory;
    }

    /** 직접 만든 판정기를 쓰고 싶을 때. null이면 기본(NCC)으로 되돌린다. */
    public static void setFactory(Factory f) {
        factory = (f != null) ? f : NCC_FACTORY;
    }

    /** 기본 NCC 판정으로 되돌린다. */
    public static void useNcc() {
        factory = NCC_FACTORY;
    }

    /**
     * YOLO(ONNX) 판정으로 전환. 기준 영상은 쓰지 않고 학습 모델이 판정한다.
     * <p>판정기를 새로 만들 때마다 모델을 다시 읽으므로, 재생성이 잦은 화면에서는
     * 첫 프레임이 조금 늦을 수 있다(모델 로드 수십 ms).
     */
    public static void useYolo(final YoloVisionJudge.Cfg cfg) {
        factory = new Factory() {
            public VisionJudge create(BufferedImage refImage, String refPath, int[] roi, double thr) {
                YoloVisionJudge.Cfg c = new YoloVisionJudge.Cfg();
                if (cfg != null) {
                    c.modelPath = cfg.modelPath;
                    c.inputSize = cfg.inputSize;
                    c.hitClassId = cfg.hitClassId;
                    c.thr = cfg.thr;
                }
                c.thr = thr;   // 임계는 설정 UI(임계 바) 값을 따른다
                YoloVisionJudge j = new YoloVisionJudge(c);
                if (roi != null) {
                    j.setRoi(roi);
                }
                return j;
            }
        };
    }

    /** 채널별 판정기 생성 - 클러스터/기어봉이 NCC / YOLO 를 서로 다르게 쓸 수 있다. */
    public static VisionJudge createFor(ApxSettings s, VisionChannel ch,
        BufferedImage refImage, String refPath, int[] roi, double thr) {
        if (s != null && s.isYoloJudge(ch)) {
            YoloVisionJudge.Cfg c = new YoloVisionJudge.Cfg();
            c.modelPath = s.getYoloModelPath(ch);
            c.inputSize = s.getYoloInputSize(ch);
            c.hitClassId = s.getYoloHitClassId(ch);
            c.thr = thr;
            YoloVisionJudge j = new YoloVisionJudge(c);
            if (roi != null) {
                j.setRoi(roi);
            }
            return j;
        }
        return NCC_FACTORY.create(refImage, refPath, roi, thr);
    }

    /** 팩토리로 판정기 생성 - 호출부는 어떤 구현인지 몰라도 된다. */
    public static VisionJudge create(BufferedImage refImage, String refPath, int[] roi, double thr) {
        return factory.create(refImage, refPath, roi, thr);
    }
}
