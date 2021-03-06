package com.mapbox.mapboxsdk.maps;

import android.graphics.PointF;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;

import com.mapbox.mapboxsdk.annotations.MarkerViewManager;
import com.mapbox.mapboxsdk.camera.CameraPosition;
import com.mapbox.mapboxsdk.camera.CameraUpdate;
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory;
import com.mapbox.mapboxsdk.constants.MapboxConstants;
import com.mapbox.mapboxsdk.geometry.LatLng;
import com.mapbox.mapboxsdk.maps.widgets.MyLocationView;

import timber.log.Timber;

import static com.mapbox.mapboxsdk.maps.MapView.REGION_DID_CHANGE_ANIMATED;

/**
 * Resembles the current Map transformation.
 * <p>
 * Responsible for synchronising {@link CameraPosition} state and notifying
 * {@link com.mapbox.mapboxsdk.maps.MapboxMap.OnCameraChangeListener}.
 * </p>
 */
final class Transform implements MapView.OnMapChangedListener {

  private final NativeMapView mapView;
  private final MarkerViewManager markerViewManager;
  private final TrackingSettings trackingSettings;
  private final MyLocationView myLocationView;

  private CameraPosition cameraPosition;
  private MapboxMap.CancelableCallback cameraCancelableCallback;
  private MapboxMap.OnCameraChangeListener onCameraChangeListener;

  Transform(NativeMapView mapView, MarkerViewManager markerViewManager, TrackingSettings trackingSettings) {
    this.mapView = mapView;
    this.markerViewManager = markerViewManager;
    this.trackingSettings = trackingSettings;
    this.myLocationView = trackingSettings.getMyLocationView();
  }

  void initialise(@NonNull MapboxMap mapboxMap, @NonNull MapboxMapOptions options) {
    CameraPosition position = options.getCamera();
    if (position != null && !position.equals(CameraPosition.DEFAULT)) {
      moveCamera(mapboxMap, CameraUpdateFactory.newCameraPosition(position), null);
    }
    setMinZoom(options.getMinZoomPreference());
    setMaxZoom(options.getMaxZoomPreference());
  }

  //
  // Camera API
  //

  @UiThread
  public final CameraPosition getCameraPosition() {
    if (cameraPosition == null) {
      cameraPosition = invalidateCameraPosition();
    }
    return cameraPosition;
  }

  @UiThread
  void updateCameraPosition(@NonNull CameraPosition position) {
    if (myLocationView != null) {
      myLocationView.setCameraPosition(position);
    }
    markerViewManager.setTilt((float) position.tilt);
  }

  @Override
  public void onMapChanged(@MapView.MapChange int change) {
    if (change == REGION_DID_CHANGE_ANIMATED && cameraCancelableCallback != null) {
      updateCameraPosition(invalidateCameraPosition());
      if (cameraCancelableCallback != null) {
        cameraCancelableCallback.onFinish();
        cameraCancelableCallback = null;
      }
      mapView.removeOnMapChangedListener(this);
    }
  }

  @UiThread
  final void moveCamera(MapboxMap mapboxMap, CameraUpdate update, MapboxMap.CancelableCallback callback) {
    CameraPosition cameraPosition = update.getCameraPosition(mapboxMap);
    if (!cameraPosition.equals(this.cameraPosition)) {
      trackingSettings.resetTrackingModesIfRequired(cameraPosition);
      cancelTransitions();
      mapView.jumpTo(cameraPosition.bearing, cameraPosition.target, cameraPosition.tilt, cameraPosition.zoom);
      if (callback != null) {
        callback.onFinish();
      }
    }
  }

  @UiThread
  final void easeCamera(MapboxMap mapboxMap, CameraUpdate update, int durationMs, boolean easingInterpolator,
                        final MapboxMap.CancelableCallback callback) {
    CameraPosition cameraPosition = update.getCameraPosition(mapboxMap);
    if (!cameraPosition.equals(this.cameraPosition)) {
      trackingSettings.resetTrackingModesIfRequired(cameraPosition);
      cancelTransitions();
      if (callback != null) {
        cameraCancelableCallback = callback;
        mapView.addOnMapChangedListener(this);
      }

      mapView.easeTo(cameraPosition.bearing, cameraPosition.target, durationMs, cameraPosition.tilt,
        cameraPosition.zoom, easingInterpolator);
    }
  }

  @UiThread
  final void animateCamera(MapboxMap mapboxMap, CameraUpdate update, int durationMs,
                           final MapboxMap.CancelableCallback callback) {
    CameraPosition cameraPosition = update.getCameraPosition(mapboxMap);
    if (!cameraPosition.equals(this.cameraPosition)) {
      trackingSettings.resetTrackingModesIfRequired(cameraPosition);

      cancelTransitions();
      if (callback != null) {
        cameraCancelableCallback = callback;
        mapView.addOnMapChangedListener(this);
      }

      mapView.flyTo(cameraPosition.bearing, cameraPosition.target, durationMs, cameraPosition.tilt,
        cameraPosition.zoom);
    }
  }

  @UiThread
  @Nullable
  CameraPosition invalidateCameraPosition() {
    if (mapView != null) {
      cameraPosition = new CameraPosition.Builder(mapView.getCameraValues()).build();
      if (onCameraChangeListener != null) {
        onCameraChangeListener.onCameraChange(this.cameraPosition);
      }
    }
    return cameraPosition;
  }

  void cancelTransitions() {
    if (cameraCancelableCallback != null) {
      cameraCancelableCallback.onCancel();
      cameraCancelableCallback = null;
    }
    mapView.cancelTransitions();
  }

  @UiThread
  void resetNorth() {
    cancelTransitions();
    mapView.resetNorth();
  }

  void setOnCameraChangeListener(@Nullable MapboxMap.OnCameraChangeListener listener) {
    this.onCameraChangeListener = listener;
  }

  //
  // non Camera API
  //

  // Zoom in or out

  double getZoom() {
    return cameraPosition.zoom;
  }

  void zoom(boolean zoomIn, @NonNull PointF focalPoint) {
    // Cancel any animation
    cancelTransitions();

    CameraPosition cameraPosition = invalidateCameraPosition();
    if (cameraPosition != null) {
      int newZoom = (int) Math.round(cameraPosition.zoom + (zoomIn ? 1 : -1));
      setZoom(newZoom, focalPoint, MapboxConstants.ANIMATION_DURATION);
    }
  }

  void setZoom(double zoom, @NonNull PointF focalPoint) {
    setZoom(zoom, focalPoint, 0);
  }

  void setZoom(double zoom, @NonNull PointF focalPoint, long duration) {
    mapView.setZoom(zoom, focalPoint, duration);
  }

  // Direction
  double getBearing() {
    double direction = -mapView.getBearing();

    while (direction > 360) {
      direction -= 360;
    }
    while (direction < 0) {
      direction += 360;
    }

    return direction;
  }

  double getRawBearing() {
    return mapView.getBearing();
  }

  void setBearing(double bearing) {
    if (myLocationView != null) {
      myLocationView.setBearing(bearing);
    }
    mapView.setBearing(bearing);
  }

  void setBearing(double bearing, float focalX, float focalY) {
    if (myLocationView != null) {
      myLocationView.setBearing(bearing);
    }
    mapView.setBearing(bearing, focalX, focalY);
  }

  void setBearing(double bearing, float focalX, float focalY, long duration) {
    if (myLocationView != null) {
      myLocationView.setBearing(bearing);
    }
    mapView.setBearing(bearing, focalX, focalY, duration);
  }


  //
  // LatLng / CenterCoordinate
  //

  LatLng getLatLng() {
    return mapView.getLatLng();
  }

  //
  // Pitch / Tilt
  //

  double getTilt() {
    return mapView.getPitch();
  }

  void setTilt(Double pitch) {
    if (myLocationView != null) {
      myLocationView.setTilt(pitch);
    }
    markerViewManager.setTilt(pitch.floatValue());
    mapView.setPitch(pitch, 0);
  }

  //
  // Center coordinate
  //

  LatLng getCenterCoordinate() {
    return mapView.getLatLng();
  }

  void setCenterCoordinate(LatLng centerCoordinate) {
    mapView.setLatLng(centerCoordinate);
  }

  void setGestureInProgress(boolean gestureInProgress) {
    mapView.setGestureInProgress(gestureInProgress);
    if (!gestureInProgress) {
      invalidateCameraPosition();
    }
  }

  void zoomBy(double z, float x, float y) {
    mapView.setZoom(mapView.getZoom() + z, new PointF(x, y), 0);
  }

  void moveBy(double offsetX, double offsetY, long duration) {
    mapView.moveBy(offsetX, offsetY, duration);
  }

  //
  // Min & Max ZoomLevel
  //

  void setMinZoom(double minZoom) {
    if ((minZoom < MapboxConstants.MINIMUM_ZOOM) || (minZoom > MapboxConstants.MAXIMUM_ZOOM)) {
      Timber.e("Not setting minZoomPreference, value is in unsupported range: " + minZoom);
      return;
    }
    mapView.setMinZoom(minZoom);
  }

  double getMinZoom() {
    return mapView.getMinZoom();
  }

  void setMaxZoom(double maxZoom) {
    if ((maxZoom < MapboxConstants.MINIMUM_ZOOM) || (maxZoom > MapboxConstants.MAXIMUM_ZOOM)) {
      Timber.e("Not setting maxZoomPreference, value is in unsupported range: " + maxZoom);
      return;
    }
    mapView.setMaxZoom(maxZoom);
  }

  double getMaxZoom() {
    return mapView.getMaxZoom();
  }
}
