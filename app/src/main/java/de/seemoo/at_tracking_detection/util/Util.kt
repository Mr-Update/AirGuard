package de.seemoo.at_tracking_detection.util

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityCompat.requestPermissions
import androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale
import androidx.core.content.ContextCompat
import de.seemoo.at_tracking_detection.ATTrackingDetectionApplication
import de.seemoo.at_tracking_detection.R
import de.seemoo.at_tracking_detection.database.models.Beacon
import de.seemoo.at_tracking_detection.database.models.Location as LocationModel
import de.seemoo.at_tracking_detection.ui.OnboardingActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import timber.log.Timber

object Util {

    const val MAX_ZOOM_LEVEL = 19.5
    const val ZOOMED_OUT_LEVEL = 15.0

    fun checkAndRequestPermission(permission: String): Boolean {
        val context = ATTrackingDetectionApplication.getCurrentActivity()
        if (context == null) {return false}
        when {
            ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED -> {
                return true
            }
            shouldShowRequestPermissionRationale(context, permission) -> {
                val bundle = Bundle().apply { putString("permission", permission) }
                val intent = Intent(context, OnboardingActivity::class.java).apply {
                    putExtras(bundle)
                }
                context.startActivity(intent)
                return false
            }
            else -> {
                requestPermissions(
                    context,
                    arrayOf(permission),
                    0
                )
                return true
            }
        }
    }

    fun checkBluetoothPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||  ActivityCompat.checkSelfPermission(ATTrackingDetectionApplication.getAppContext(), Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    }

    fun enableMyLocationOverlay(
        map:MapView
    ) {
        val context = ATTrackingDetectionApplication.getAppContext()
        val locationOverlay = MyLocationNewOverlay(map)
        val options = BitmapFactory.Options()
        val bitmapPerson =
            BitmapFactory.decodeResource(context.resources, R.drawable.mylocation, options)
        locationOverlay.setPersonIcon(bitmapPerson)
        locationOverlay.setPersonHotspot((26.0 * 1.6).toFloat(), (26.0 * 1.6).toFloat())
        locationOverlay.setDirectionArrow(bitmapPerson, bitmapPerson)
        locationOverlay.enableMyLocation()
        locationOverlay.enableFollowLocation()
        map.overlays.add(locationOverlay)
        map.controller.setZoom(ZOOMED_OUT_LEVEL)
    }

    suspend fun setGeoPointsFromListOfBeacons( // TODO: remove in future and replace with Location?
        beaconList: List<Beacon>,
        map: MapView,
        connectWithPolyline: Boolean = false,
        onMarkerWindowClick: ((beacon: Beacon) -> Unit)? = null
    ): Boolean {

        val context = ATTrackingDetectionApplication.getAppContext()
        val copyrightOverlay = CopyrightOverlay(context)

        val mapController = map.controller
        val geoPointList = ArrayList<GeoPoint>()
        val markerList = ArrayList<Marker>()


        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setUseDataConnection(true)
        map.setMultiTouchControls(true)

        map.overlays.add(copyrightOverlay)

        val locationRepository = ATTrackingDetectionApplication.getCurrentApp()?.locationRepository!!

        // Causes crashes when the view gets destroyed and markers are still added. Will get fixed in the next Version!
        withContext(Dispatchers.Default) {
            beaconList
                .filter { it.locationId != null && it.locationId != 0 }
                .map { beacon ->
                    if (!map.isShown) {
                        return@map
                    }
                    val location = locationRepository.getLocationWithId(beacon.locationId!!)!!
                    val marker = Marker(map)
                    val geoPoint = GeoPoint(location.latitude, location.longitude)
                    marker.infoWindow = DeviceMarkerInfo(
                        R.layout.include_device_marker_window, map, beacon, onMarkerWindowClick
                    )
                    marker.position = geoPoint
                    marker.icon = ContextCompat.getDrawable(
                        context,
                        R.drawable.ic_baseline_location_on_45_black
                    )
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    geoPointList.add(geoPoint)
                    markerList.add(marker)

                    marker.setOnMarkerClickListener { clickedMarker, _ ->
                        if (clickedMarker.isInfoWindowShown) {
                            clickedMarker.closeInfoWindow()
                        } else {
                            clickedMarker.showInfoWindow()
                        }
                        true
                    }
                }
        }
        map.overlays.addAll(markerList)

        Timber.d("Added ${geoPointList.size} markers to the map!")

        if (connectWithPolyline) {
            val line = Polyline(map)
            line.setPoints(geoPointList)
            line.infoWindow = null
            map.overlays.add(line)
        }
        if (geoPointList.isEmpty()) {
            mapController.setZoom(MAX_ZOOM_LEVEL)
            return false
        }
        val myLocationOverlay = map.overlays.firstOrNull{ it is MyLocationNewOverlay} as? MyLocationNewOverlay
        myLocationOverlay?.disableFollowLocation()
        val boundingBox = BoundingBox.fromGeoPointsSafe(geoPointList)

        map.post {
            try {
                Timber.d("Zoom in to bounds -> $boundingBox")
                map.zoomToBoundingBox(boundingBox, true, 100, MAX_ZOOM_LEVEL, 1)

            } catch (e: IllegalArgumentException) {
                mapController.setCenter(boundingBox.centerWithDateLine)
                mapController.setZoom(10.0)
                Timber.e("Failed to zoom to bounding box! ${e.message}")
            }
        }

        // map.zoomToBoundingBox(boundingBox, true)

        return true
    }

    suspend fun setGeoPointsFromListOfLocations(
        locationList: List<LocationModel>,
        map: MapView,
        connectWithPolyline: Boolean = false,
        // TODO: onMarkerWindowClick: ((location: LocationModel) -> List<Beacon>)? = null
    ): Boolean {

        val context = ATTrackingDetectionApplication.getAppContext()
        val copyrightOverlay = CopyrightOverlay(context)

        val mapController = map.controller
        val geoPointList = ArrayList<GeoPoint>()
        val markerList = ArrayList<Marker>()


        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setUseDataConnection(true)
        map.setMultiTouchControls(true)

        map.overlays.add(copyrightOverlay)

        // Causes crashes when the view gets destroyed and markers are still added. Will get fixed in the next Version!
        withContext(Dispatchers.Default) {
            locationList
                .filter { it.locationId != 0 } // TODO: unnecessary if zero fixed
                .map { location ->
                    if (!map.isShown) {
                        return@map
                    }
                    val marker = Marker(map)
                    val geoPoint = GeoPoint(location.latitude, location.longitude)
                    /*
                    marker.infoWindow = DeviceMarkerInfo(
                        R.layout.include_device_marker_window, map, beacon, onMarkerWindowClick // TODO modify
                    )
                     */
                    marker.position = geoPoint
                    marker.icon = ContextCompat.getDrawable(
                        context,
                        R.drawable.ic_baseline_location_on_45_black
                    )
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    geoPointList.add(geoPoint)
                    markerList.add(marker)

                    marker.setOnMarkerClickListener { clickedMarker, _ ->
                        if (clickedMarker.isInfoWindowShown) {
                            clickedMarker.closeInfoWindow()
                        } else {
                            clickedMarker.showInfoWindow()
                        }
                        true
                    }
                }
        }
        map.overlays.addAll(markerList)

        Timber.d("Added ${geoPointList.size} markers to the map!")

        if (connectWithPolyline) {
            val line = Polyline(map)
            line.setPoints(geoPointList)
            line.infoWindow = null
            map.overlays.add(line)
        }
        if (geoPointList.isEmpty()) {
            mapController.setZoom(MAX_ZOOM_LEVEL)
            return false
        }
        val myLocationOverlay = map.overlays.firstOrNull{ it is MyLocationNewOverlay} as? MyLocationNewOverlay
        myLocationOverlay?.disableFollowLocation()
        val boundingBox = BoundingBox.fromGeoPointsSafe(geoPointList)

        map.post {
            try {
                Timber.d("Zoom in to bounds -> $boundingBox")
                map.zoomToBoundingBox(boundingBox, true, 100, MAX_ZOOM_LEVEL, 1)

            } catch (e: IllegalArgumentException) {
                mapController.setCenter(boundingBox.centerWithDateLine)
                mapController.setZoom(10.0)
                Timber.e("Failed to zoom to bounding box! ${e.message}")
            }
        }

        // map.zoomToBoundingBox(boundingBox, true)

        return true
    }


    fun setSelectedTheme(sharedPreferences: SharedPreferences) {
        when (sharedPreferences.getString("app_theme", "system_default")) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "system_default" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }
}
