package io.github.iamth3doctor.searchlight.ui

import android.app.AlertDialog
import android.content.DialogInterface
import android.widget.ArrayAdapter
import io.github.iamth3doctor.searchlight.R

/*
 * Dialog lists whose rows wrap to two lines. The platform's own rows are single-line and cut
 * long names off mid-word with no ellipsis (e.g. a long music-app name).
 */

fun AlertDialog.Builder.wrappingSingleChoice(
    labels: List<CharSequence>,
    checked: Int,
    onPick: (DialogInterface, Int) -> Unit,
): AlertDialog.Builder =
    setSingleChoiceItems(ArrayAdapter(context, R.layout.item_dialog_choice, labels), checked) { d, which -> onPick(d, which) }

fun AlertDialog.Builder.wrappingItems(
    labels: List<CharSequence>,
    onPick: (DialogInterface, Int) -> Unit,
): AlertDialog.Builder =
    setAdapter(ArrayAdapter(context, R.layout.item_dialog_item, labels)) { d, which -> onPick(d, which) }
