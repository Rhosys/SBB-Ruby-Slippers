package ch.rhosys.sbb.wear.tile

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.ChipDefaults
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import ch.rhosys.sbb.wear.PhoneClient
import ch.rhosys.sbb.wear.R
import ch.rhosys.sbb.wear.WearMainActivity
import ch.rhosys.sbb.wear.WearPlace
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val RESOURCES_VERSION = "2"
private const val TRAIN_IMAGE_ID = "train_front"
// What fits in a tile's content area as compact chips; the rest are behind "More".
private const val MAX_TILE_PLACES = 3

// "Go to" tile: the places on the phone's home screen. Tapping one opens the watch app on
// the next connections from the current location to that place, where one can be saved.
class PlacesTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            scope.launch {
                runCatching {
                    val places = PhoneClient(this@PlacesTileService).places()
                    TileBuilders.Tile.Builder()
                        .setResourcesVersion(RESOURCES_VERSION)
                        .setTileTimeline(
                            TimelineBuilders.Timeline.fromLayoutElement(
                                layout(this@PlacesTileService, requestParams.deviceConfiguration, places)
                            )
                        )
                        .build()
                }.fold(completer::set, completer::setException)
            }
            "PlacesTileService.onTileRequest"
        }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(
                ResourceBuilders.Resources.Builder()
                    .setVersion(RESOURCES_VERSION)
                    .addIdToImageMapping(
                        TRAIN_IMAGE_ID,
                        ResourceBuilders.ImageResource.Builder()
                            .setAndroidResourceByResId(
                                ResourceBuilders.AndroidImageResourceByResId.Builder()
                                    .setResourceId(R.drawable.ic_train_front)
                                    .build()
                            )
                            .build(),
                    )
                    .build()
            )
            "PlacesTileService.onTileResourcesRequest"
        }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

// The places over a faint train front in the middle of the tile.
private fun layout(
    context: Context,
    device: DeviceParameters,
    places: List<WearPlace>,
): LayoutElementBuilders.LayoutElement =
    LayoutElementBuilders.Box.Builder()
        .setWidth(DimensionBuilders.expand())
        .setHeight(DimensionBuilders.expand())
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        .addContent(
            LayoutElementBuilders.Image.Builder()
                .setResourceId(TRAIN_IMAGE_ID)
                .setWidth(DimensionBuilders.dp(TRAIN_IMAGE_SIZE_DP))
                .setHeight(DimensionBuilders.dp(TRAIN_IMAGE_SIZE_DP))
                .setColorFilter(
                    LayoutElementBuilders.ColorFilter.Builder()
                        .setTint(ColorBuilders.argb(TRAIN_IMAGE_TINT))
                        .build()
                )
                .build()
        )
        .addContent(placesLayout(context, device, places))
        .build()

private fun placesLayout(
    context: Context,
    device: DeviceParameters,
    places: List<WearPlace>,
): LayoutElementBuilders.LayoutElement {
    val builder = PrimaryLayout.Builder(device)
        .setPrimaryLabelTextContent(
            Text.Builder(context, "Go to")
                .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                .setColor(ColorBuilders.argb(SBB_RED))
                .build()
        )
    if (places.isEmpty()) {
        return builder.setContent(
            Text.Builder(context, "Add places on your phone's home screen")
                .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                .setColor(ColorBuilders.argb(0xFFFFFFFF.toInt()))
                .setMaxLines(3)
                .build()
        ).build()
    }
    val column = LayoutElementBuilders.Column.Builder()
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
    places.take(MAX_TILE_PLACES).forEachIndexed { i, place ->
        if (i > 0) column.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(4f)).build())
        column.addContent(
            CompactChip.Builder(context, place.name, openApp(context, "place_${place.id}", place.id), device)
                .setChipColors(ChipDefaults.SECONDARY_COLORS)
                .build()
        )
    }
    builder.setContent(column.build())
    if (places.size > MAX_TILE_PLACES) {
        builder.setPrimaryChipContent(
            CompactChip.Builder(context, "More", openApp(context, "more", null), device).build()
        )
    }
    return builder.build()
}

private fun openApp(context: Context, id: String, placeId: Long?): ModifiersBuilders.Clickable {
    val activity = ActionBuilders.AndroidActivity.Builder()
        .setPackageName(context.packageName)
        .setClassName(WearMainActivity::class.java.name)
    if (placeId != null) {
        activity.addKeyToExtraMapping(
            WearMainActivity.EXTRA_PLACE_ID,
            ActionBuilders.AndroidLongExtra.Builder().setValue(placeId).build(),
        )
    }
    return ModifiersBuilders.Clickable.Builder()
        .setId(id)
        .setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity.build()).build())
        .build()
}

private const val SBB_RED = 0xFFE3001B.toInt()
private const val TRAIN_IMAGE_SIZE_DP = 120f
// White at ~30% opacity: visible behind the chips without competing with their text.
private const val TRAIN_IMAGE_TINT = 0x4DFFFFFF
