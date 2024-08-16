//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package org.webrtc;

import android.graphics.Point;
import android.opengl.Matrix;
import android.view.View;
import android.view.View.MeasureSpec;

public class RendererCommon {
  private static float BALANCED_VISIBLE_FRACTION = 0.5625F;

  public RendererCommon() {
  }

  public static float[] getLayoutMatrix(boolean mirror, float videoAspectRatio, float displayAspectRatio) {
    float scaleX = 1.0F;
    float scaleY = 1.0F;
    if (displayAspectRatio > videoAspectRatio) {
      scaleY = videoAspectRatio / displayAspectRatio;
    } else {
      scaleX = displayAspectRatio / videoAspectRatio;
    }

    if (mirror) {
      scaleX *= -1.0F;
    }

    float[] matrix = new float[16];
    Matrix.setIdentityM(matrix, 0);
    Matrix.scaleM(matrix, 0, scaleX, scaleY, 1.0F);
    adjustOrigin(matrix);
    return matrix;
  }

  public static android.graphics.Matrix convertMatrixToAndroidGraphicsMatrix(float[] matrix4x4) {
    float[] values = {
            matrix4x4[0 * 4 + 0], matrix4x4[1 * 4 + 0], matrix4x4[3 * 4 + 0],
            matrix4x4[0 * 4 + 1], matrix4x4[1 * 4 + 1], matrix4x4[3 * 4 + 1],
            matrix4x4[0 * 4 + 3], matrix4x4[1 * 4 + 3], matrix4x4[3 * 4 + 3],
    };
    android.graphics.Matrix matrix = new android.graphics.Matrix();
    matrix.setValues(values);
    return matrix;
  }

  public static float[] convertMatrixFromAndroidGraphicsMatrix(android.graphics.Matrix matrix) {
    float[] values = new float[9];
    matrix.getValues(values);
    float[] matrix4x4 = {
            values[0 * 3 + 0],  values[1 * 3 + 0], 0,  values[2 * 3 + 0],
            values[0 * 3 + 1],  values[1 * 3 + 1], 0,  values[2 * 3 + 1],
            0,                  0,                 1,  0,
            values[0 * 3 + 2],  values[1 * 3 + 2], 0,  values[2 * 3 + 2],
    };
    return matrix4x4;
  }

  public static Point getDisplaySize(ScalingType scalingType, float videoAspectRatio, int maxDisplayWidth, int maxDisplayHeight) {
    return getDisplaySize(convertScalingTypeToVisibleFraction(scalingType), videoAspectRatio, maxDisplayWidth, maxDisplayHeight);
  }

  private static void adjustOrigin(float[] matrix) {
    matrix[12] -= 0.5f * (matrix[0] + matrix[4]);
    matrix[13] -= 0.5f * (matrix[1] + matrix[5]);
    matrix[12] += 0.5f;
    matrix[13] += 0.5f;
  }

  private static float convertScalingTypeToVisibleFraction(ScalingType scalingType) {
    switch (scalingType) {
      case SCALE_ASPECT_FIT:
        return 1.0F;
      case SCALE_ASPECT_FILL:
        return 0.0F;
      case SCALE_ASPECT_BALANCED:
        return BALANCED_VISIBLE_FRACTION;
      default:
        throw new IllegalArgumentException();
    }
  }

  public static Point getDisplaySize(float minVisibleFraction, float videoAspectRatio, int maxDisplayWidth, int maxDisplayHeight) {
    if (minVisibleFraction != 0.0F && videoAspectRatio != 0.0F) {
      final int width = Math.min(maxDisplayWidth, Math.round(maxDisplayHeight / minVisibleFraction * videoAspectRatio));
      final int height = Math.min(maxDisplayHeight, Math.round(maxDisplayWidth / minVisibleFraction / videoAspectRatio));
      return new Point(width, height);
    } else {
      return new Point(maxDisplayWidth, maxDisplayHeight);
    }
  }

  public static enum ScalingType {
    SCALE_ASPECT_FIT,
    SCALE_ASPECT_FILL,
    SCALE_ASPECT_BALANCED;

    private ScalingType() {
    }
  }

  public static class VideoLayoutMeasure {
    private float visibleFractionMatchOrientation;
    private float visibleFractionMismatchOrientation;

    public VideoLayoutMeasure() {
      this.visibleFractionMatchOrientation = RendererCommon.convertScalingTypeToVisibleFraction(RendererCommon.ScalingType.SCALE_ASPECT_BALANCED);
      this.visibleFractionMismatchOrientation = RendererCommon.convertScalingTypeToVisibleFraction(RendererCommon.ScalingType.SCALE_ASPECT_BALANCED);
    }

    public void setScalingType(ScalingType scalingType) {
      this.setScalingType(scalingType, scalingType);
    }

    public void setScalingType(ScalingType scalingTypeMatchOrientation, ScalingType scalingTypeMismatchOrientation) {
      this.visibleFractionMatchOrientation = RendererCommon.convertScalingTypeToVisibleFraction(scalingTypeMatchOrientation);
      this.visibleFractionMismatchOrientation = RendererCommon.convertScalingTypeToVisibleFraction(scalingTypeMismatchOrientation);
    }

    public void setVisibleFraction(float visibleFractionMatchOrientation, float visibleFractionMismatchOrientation) {
      this.visibleFractionMatchOrientation = visibleFractionMatchOrientation;
      this.visibleFractionMismatchOrientation = visibleFractionMismatchOrientation;
    }

    public Point measure(int widthSpec, int heightSpec, int frameWidth, int frameHeight) {
      int maxWidth = View.getDefaultSize(Integer.MAX_VALUE, widthSpec);
      int maxHeight = View.getDefaultSize(Integer.MAX_VALUE, heightSpec);
      if (frameWidth != 0 && frameHeight != 0 && maxWidth != 0 && maxHeight != 0) {
        final float frameAspect = frameWidth / (float) frameHeight;
        final float displayAspect = maxWidth / (float) maxHeight;
        final float visibleFraction = (frameAspect > 1.0f) == (displayAspect > 1.0f)
                ? visibleFractionMatchOrientation
                : visibleFractionMismatchOrientation;
        Point layoutSize = RendererCommon.getDisplaySize(visibleFraction, frameAspect, maxWidth, maxHeight);
        if (MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY) {
          layoutSize.x = maxWidth;
        }

        if (MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY) {
          layoutSize.y = maxHeight;
        }

        return layoutSize;
      } else {
        return new Point(maxWidth, maxHeight);
      }
    }
  }

  public interface GlDrawer {
    void drawOes(int oesTextureId, float[] texMatrix, int frameWidth, int frameHeight,
                 int viewportX, int viewportY, int viewportWidth, int viewportHeight);
    void drawRgb(int textureId, float[] texMatrix, int frameWidth, int frameHeight, int viewportX,
                 int viewportY, int viewportWidth, int viewportHeight);
    void drawYuv(int[] yuvTextures, float[] texMatrix, int frameWidth, int frameHeight,
                 int viewportX, int viewportY, int viewportWidth, int viewportHeight);

    void release();
  }

  public interface RendererEvents {
    void onFirstFrameRendered();

    void onFrameResolutionChanged(int videoWidth, int videoHeight, int rotation);
  }
}
