package com.example.wisehome.ui.screens.home

import androidx.annotation.DrawableRes
import com.example.wisehome.R

/**
 * Floor plan artwork bundled with the app, resolved from `floors.image_url`.
 *
 * The database stores a path ("floorplans/ground.png") rather than a resource id, so
 * the same value works for the web simulator and survives a floor being created from
 * the app. An unrecognised value resolves to null and the grid simply renders on its
 * own — a floor the user adds is usable before any artwork exists for it.
 */
@DrawableRes
fun floorPlanDrawable(imageUrl: String?): Int? =
    when (imageUrl?.substringAfterLast('/')?.substringBeforeLast('.')?.lowercase()) {
        "ground" -> R.drawable.floorplan_ground
        "first" -> R.drawable.floorplan_first
        "exterior" -> R.drawable.floorplan_exterior
        else -> null
    }

/**
 * Geometry of the bundled plans, which are generated from the same room rectangles
 * the app draws: [PLAN_CELL] pixels per grid cell inside a [PLAN_PADDING] border.
 * The room map uses these to crop the plan to the room being viewed, so the artwork
 * lines up with the grid instead of floating behind it.
 */
const val PLAN_CELL = 120
const val PLAN_PADDING = 28
