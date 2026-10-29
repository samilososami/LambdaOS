/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.setupwizard;

import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.NumberPicker;

/** Native wheel accessibility/keyboard navigation; no editable field or IME. */
final class LambdaLanguageWheel extends FrameLayout {
    final NumberPicker picker;
    LambdaLanguageWheel(Context context,LambdaUi ui,String[] labels,int selected,
            java.util.function.IntConsumer choose) {
        super(context);
        android.view.View highlight=new android.view.View(context);
        highlight.setBackground(ui.shape(ui.color(0xff292929),14));
        LayoutParams hp=new LayoutParams(-1,ui.dp(54),Gravity.CENTER);
        hp.setMargins(ui.dp(4),0,ui.dp(4),0);addView(highlight,hp);
        picker=new NumberPicker(context);
        picker.setMinValue(0);picker.setMaxValue(labels.length-1);
        picker.setDisplayedValues(labels);picker.setValue(selected);
        picker.setWrapSelectorWheel(false);
        picker.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        picker.setSelectionDividerHeight(0);
        picker.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,19,context.getResources().getDisplayMetrics()));picker.setTextColor(ui.color(LambdaUi.FG));
        for(int i=0;i<picker.getChildCount();i++)if(picker.getChildAt(i) instanceof EditText){
            EditText input=(EditText)picker.getChildAt(i);input.setTypeface(ui.regular);
            input.setTextColor(ui.color(LambdaUi.FG));input.setSingleLine(true);input.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        }
        picker.setOnValueChangedListener((p,oldValue,newValue)->choose.accept(newValue));
        addView(picker,new LayoutParams(-1,ui.dp(222),Gravity.CENTER));
        setMinimumHeight(ui.dp(238));
    }
}
