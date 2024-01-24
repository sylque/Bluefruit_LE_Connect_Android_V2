package com.adafruit.bluefruit.le.connect.app;

import static android.Manifest.permission.BLUETOOTH_CONNECT;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothGatt;
import android.content.Context;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;

import com.adafruit.bluefruit.le.connect.R;
import com.adafruit.bluefruit.le.connect.ble.central.BlePeripheral;
import com.adafruit.bluefruit.le.connect.ble.central.BlePeripheralUart;
import com.adafruit.bluefruit.le.connect.ble.central.BleScanner;
import com.adafruit.bluefruit.le.connect.ble.central.UartDataManager;
import com.adafruit.bluefruit.le.connect.style.UartStyle;
import com.adafruit.bluefruit.le.connect.utils.DialogUtils;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlotterFragment extends ConnectedPeripheralFragment implements UartDataManager.UartDataManagerListener {
    // Log
    private final static String TAG = PlotterFragment.class.getSimpleName();

    // Config
    private final static int xMaxEntriesMin = 6;
    private final static int xMaxEntriesMax = 100;

    // UI
    private SeekBar xMaxEntriesSeekBar;
    private LineChart mChart;

    // Data
    private UartDataManager mUartDataManager;
    private long mOriginTimestamp;
    private final @NonNull List<BlePeripheralUart> mBlePeripheralsUart = new ArrayList<>();
    private boolean mIsAutoScrollEnabled = true;
    private int mVisibleInterval = 20;        // in seconds
    private final Map<String, DashPathEffect> mLineDashPathEffectForPeripheral = new HashMap<>();
    private final Map<String, List<LineDataSet>> mDataSetsForPeripheral = new HashMap<>();
    private LineDataSet mLastDataSetModified;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private SubMenu mSelectSubmenu;

    // region Fragment Lifecycle
    public static PlotterFragment newInstance(@Nullable String singlePeripheralIdentifier) {
        PlotterFragment fragment = new PlotterFragment();
        fragment.setArguments(createFragmentArgs(singlePeripheralIdentifier));
        return fragment;
    }

    public PlotterFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Retain this fragment across configuration changes
        setRetainInstance(true);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_plotter, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Update ActionBar
        setActionBarTitle(R.string.plotter_tab_title);

        // UI
        mChart = view.findViewById(R.id.chart);
        WeakReference<PlotterFragment> weakThis = new WeakReference<>(this);
        SwitchCompat autoscrollSwitch = view.findViewById(R.id.autoscrollSwitch);
        autoscrollSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (buttonView.isPressed()) {
                PlotterFragment fragment = weakThis.get();          // Fix detected memory leak
                if (fragment != null) {
                    fragment.mIsAutoScrollEnabled = isChecked;
                    fragment.mChart.setDragEnabled(!isChecked);
                    fragment.notifyDataSetChanged();
                }
            }
        });
        xMaxEntriesSeekBar = view.findViewById(R.id.xMaxEntriesSeekBar);
        xMaxEntriesSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    final float factor = progress / 100.f;
                    mVisibleInterval = Math.round((xMaxEntriesMax - xMaxEntriesMin) * factor + xMaxEntriesMin);
                    notifyDataSetChanged();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        autoscrollSwitch.setChecked(mIsAutoScrollEnabled);
        mChart.setDragEnabled(!mIsAutoScrollEnabled);
        setXMaxEntriesValue(mVisibleInterval);

        // Setup
        Context context = getContext();
        if (context != null) {
            mUartDataManager = new UartDataManager(context, this, true);
            mOriginTimestamp = System.currentTimeMillis();

            setupChart();

            try {
                setupUart();
            } catch (SecurityException e) {
                Log.e(TAG, "onViewCreated security exception: " + e);
            }
        }
    }

    @Override
    public void onDestroy() {
        if (mUartDataManager != null) {
            Context context = getContext();
            if (context != null) {
                mUartDataManager.setEnabled(context, false);
            }
        }

        for (BlePeripheralUart blePeripheralUart : mBlePeripheralsUart) {
            blePeripheralUart.uartDisable();
        }
        mBlePeripheralsUart.clear();

        super.onDestroy();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.menu_plotter, menu);
        mSelectSubmenu = menu.getItem(0).getSubMenu();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        FragmentActivity activity = getActivity();

        switch (item.getItemId()) {
            case R.id.action_help:
                if (activity != null) {
                    FragmentManager fragmentManager = activity.getSupportFragmentManager();
                    CommonHelpFragment helpFragment = CommonHelpFragment.newInstance(getString(R.string.plotter_help_title), getString(R.string.plotter_help_text));
                    FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction()
                            .replace(R.id.contentLayout, helpFragment, "Help");
                    fragmentTransaction.addToBackStack(null);
                    fragmentTransaction.commit();
                }
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }
    // endregion


    // region Uart

    private boolean isInMultiUartMode() {
        return mBlePeripheral == null;
    }

    @SuppressLint("InlinedApi")
    @RequiresPermission(value = BLUETOOTH_CONNECT)
    private void setupUart() {
        // Line dashes assigned to peripherals
        final DashPathEffect[] dashPathEffects = UartStyle.defaultDashPathEffects();

        // Enable uart
        if (isInMultiUartMode()) {
            mLineDashPathEffectForPeripheral.clear();   // Reset line dashes assigned to peripherals
            List<BlePeripheral> connectedPeripherals = BleScanner.getInstance().getConnectedPeripherals();
            for (int i = 0; i < connectedPeripherals.size(); i++) {
                BlePeripheral blePeripheral = connectedPeripherals.get(i);
                mLineDashPathEffectForPeripheral.put(blePeripheral.getIdentifier(), dashPathEffects[i % dashPathEffects.length]);

                if (!BlePeripheralUart.isUartInitialized(blePeripheral, mBlePeripheralsUart)) {
                    BlePeripheralUart blePeripheralUart = new BlePeripheralUart(blePeripheral);
                    mBlePeripheralsUart.add(blePeripheralUart);
                    blePeripheralUart.uartEnable(mUartDataManager, status -> {

                        String peripheralName = blePeripheral.getName();
                        if (peripheralName == null) {
                            peripheralName = blePeripheral.getIdentifier();
                        }

                        String finalPeripheralName = peripheralName;
                        mMainHandler.post(() -> {
                            if (status == BluetoothGatt.GATT_SUCCESS) {
                                // Done
                                Log.d(TAG, "Uart enabled for: " + finalPeripheralName);
                            } else {
                                //WeakReference<BlePeripheralUart> weakBlePeripheralUart = new WeakReference<>(blePeripheralUart);
                                AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                                AlertDialog dialog = builder.setMessage(String.format(getString(R.string.uart_error_multipleperiperipheralinit_format), finalPeripheralName))
                                        .setPositiveButton(android.R.string.ok, (dialogInterface, which) -> {
                                        /*
                                            BlePeripheralUart strongBlePeripheralUart = weakBlePeripheralUart.get();
                                        if (strongBlePeripheralUart != null) {
                                            strongBlePeripheralUart.disconnect();
                                        }*/
                                        })
                                        .show();
                                DialogUtils.keepDialogOnOrientationChanges(dialog);
                            }
                        });

                    });
                }
            }

        } else {       //  Single peripheral mode
            if (!BlePeripheralUart.isUartInitialized(mBlePeripheral, mBlePeripheralsUart)) { // If was not previously setup (i.e. orientation change)
                mLineDashPathEffectForPeripheral.clear();   // Reset line dashes assigned to peripherals
                mLineDashPathEffectForPeripheral.put(mBlePeripheral.getIdentifier(), dashPathEffects[0]);
                BlePeripheralUart blePeripheralUart = new BlePeripheralUart(mBlePeripheral);
                mBlePeripheralsUart.add(blePeripheralUart);
                blePeripheralUart.uartEnable(mUartDataManager, status -> mMainHandler.post(() -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        // Done
                        Log.d(TAG, "Uart enabled");
                    } else {
                        Context context = getContext();
                        if (context != null) {
                            WeakReference<BlePeripheralUart> weakBlePeripheralUart = new WeakReference<>(blePeripheralUart);
                            AlertDialog.Builder builder = new AlertDialog.Builder(context);
                            AlertDialog dialog = builder.setMessage(R.string.uart_error_peripheralinit)
                                    .setPositiveButton(android.R.string.ok, (dialogInterface, which) -> {
                                        BlePeripheralUart strongBlePeripheralUart = weakBlePeripheralUart.get();
                                        if (strongBlePeripheralUart != null) {
                                            try {
                                                strongBlePeripheralUart.disconnect();
                                            } catch (SecurityException e) {
                                                Log.e(TAG, "security exception: " + e);
                                            }
                                        }
                                    })
                                    .show();
                            DialogUtils.keepDialogOnOrientationChanges(dialog);
                        }
                    }
                }));
            }
        }
    }

    // endregion

    // region Line Chart
    private void setupChart() {

        mChart.getDescription().setEnabled(false);
        mChart.getXAxis().setGranularityEnabled(true);
        mChart.getXAxis().setGranularity(5);

        mChart.setExtraOffsets(10, 10, 10, 0);
        mChart.getLegend().setEnabled(false);

        mChart.setNoDataTextColor(Color.BLACK);
        mChart.setNoDataText(getString(R.string.plotter_nodata));

        // MPAndroidChart Documentation:
        // https://weeklycoding.com/mpandroidchart-documentation/

        // Hide the right axis
        mChart.getAxisRight().setEnabled(false);

        // Enable dynamic Y axis downscaling. See:
        // https://stackoverflow.com/questions/31934301/how-to-continuously-update-y-axis-values-in-mpandroidchart
        mChart.setAutoScaleMinMaxEnabled(true);

        // Enable scrolling the chart. See:
        // https://github.com/PhilJay/MPAndroidChart/wiki/Interaction-with-the-Chart
        mChart.setDragEnabled(true);
    }

    private void setXMaxEntriesValue(int value) {
        final float percent = Math.max(0, (value - xMaxEntriesMin)) / (float) (xMaxEntriesMax - xMaxEntriesMin);
        final int progress = Math.round(percent * xMaxEntriesSeekBar.getMax());
        xMaxEntriesSeekBar.setProgress(progress);
    }

    private void addEntry(@NonNull String peripheralIdentifier, int index, float value, float timestamp) {
        Entry entry = new Entry(timestamp, value);

        boolean dataSetExists = false;
        List<LineDataSet> dataSets = mDataSetsForPeripheral.get(peripheralIdentifier);
        if (dataSets != null) {
            if (index < dataSets.size()) {
                // Add entry to existing dataset
                LineDataSet dataSet = dataSets.get(index);
                dataSet.addEntry(entry);
                dataSetExists = true;
            }
        }

        if (!dataSetExists) {
            LineDataSet dataSet = appendDataset(peripheralIdentifier, entry, index);

            List<ILineDataSet> allDataSets = new ArrayList<>();
            for (List<LineDataSet> dataSetLists : mDataSetsForPeripheral.values()) {
                allDataSets.addAll(dataSetLists);
            }
            final LineData lineData = new LineData(allDataSets);
            mChart.setData(lineData);

            // Populate the data stream selection submenu with a new item
            MenuItem item = mSelectSubmenu.add(0, Menu.NONE, index, "Stream " + (index+1));
            item.setCheckable(true);
            item.setChecked(true);
            Drawable iconDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.baseline_timeline_24);
            iconDrawable.setColorFilter(allDataSets.get(index).getColor(), PorterDuff.Mode.SRC_IN);
            item.setIcon(iconDrawable);

            item.setOnMenuItemClickListener(item1 -> {
                // Check the checkbox
                item1.setChecked(!item1.isChecked());

                // Show/hide the data line
                dataSet.setVisible(item1.isChecked());
                mChart.invalidate();

                // Keep the popup menu open. See:
                // https://stackoverflow.com/a/31727213/3567351
                item1.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
                item1.setActionView(new View(getContext()));
                item1.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
                    @Override
                    public boolean onMenuItemActionExpand(MenuItem item1) { return false; }
                    @Override
                    public boolean onMenuItemActionCollapse(MenuItem item1) { return false; }
                });
                return false;
            });
        }

        List<LineDataSet> dataSets2 = mDataSetsForPeripheral.get(peripheralIdentifier);
        if (dataSets2 != null && index < dataSets2.size()) {
            mLastDataSetModified = dataSets2.get(index);
        }
    }

    private void notifyDataSetChanged() {
        if (mChart.getData() != null) {
            mChart.getData().notifyDataChanged();
        }
        mChart.notifyDataSetChanged();
        mChart.invalidate();
        mChart.setVisibleXRangeMaximum(mVisibleInterval);
        mChart.setVisibleXRangeMinimum(mVisibleInterval);

        if (mLastDataSetModified != null && mIsAutoScrollEnabled) {
            final List<Entry> values = mLastDataSetModified.getValues();

            float x = 0;
            if (values != null && values.size() > 0) {
                Entry value = values.get(values.size() - 1);
                if (value != null) {
                    x = value.getX();
                }
            }

            final float xOffset = x - (mVisibleInterval - 1);
            mChart.moveViewToX(xOffset);
        }
    }

    private LineDataSet appendDataset(@NonNull String peripheralIdentifier, @NonNull Entry entry, int index) {
        LineDataSet dataSet = new LineDataSet(null, "Values[" + peripheralIdentifier + ":" + index + "]");
        dataSet.addEntry(entry);
        dataSet.addEntry(entry);

        dataSet.setDrawCircles(false);
        dataSet.setDrawValues(false);
        dataSet.setLineWidth(2);
        final int[] colors = UartStyle.defaultColors();
        final int color = colors[index % colors.length];
        dataSet.setColor(color);
        final DashPathEffect dashPatternEffect = mLineDashPathEffectForPeripheral.get(peripheralIdentifier);
        dataSet.setFormLineDashEffect(dashPatternEffect);

        List<LineDataSet> previousDataSets = mDataSetsForPeripheral.get(peripheralIdentifier);
        if (previousDataSets != null) {
            previousDataSets.add(dataSet);
        } else {
            List<LineDataSet> dataSets = new ArrayList<>();
            dataSets.add(dataSet);
            mDataSetsForPeripheral.put(peripheralIdentifier, dataSets);
        }

        return dataSet;
    }

    // endregion

    // region UartDataManagerListener
    private static final byte kLineSeparator = 10;

    @Override
    public void onUartRx(@NonNull byte[] data, @Nullable String peripheralIdentifier) {
        /*
        Log.d(TAG, "uart rx read (hex): " + BleUtils.bytesToHex2(data));
        try {
            Log.d(TAG, "uart rx read (utf8): " + new String(data, "UTF-8"));
        } catch (UnsupportedEncodingException ignored) {
        }*/

        // There is an issue with the original code: when opening the plotter, we might receive an
        // incomplete line. For example, line "0.11111 0.22222 0.33333" might arrive as "11111
        // 0.22222 0.33333", which will mess up with the Y scale. So we need to ensure we only take
        // complete lines into consideration.

        // Convert byte array to string
        final String dataString = new String(data, StandardCharsets.UTF_8);

        // Iterate over the data to find full lines (i.e. between two separators)
        int lastPos = 0;
        while(true) {
            // Find first and last separators. This ensures any line is complete and any line with a
            // missing beginning is ignored.
            final int firstPos = dataString.indexOf(kLineSeparator, lastPos);
            if (firstPos == -1) {
                break;
            }
            lastPos = dataString.indexOf(kLineSeparator, firstPos + 1);
            if (lastPos == -1) {
                break;
            }

            // A full line has been found
            final float currentTimestamp = (System.currentTimeMillis() - mOriginTimestamp) / 1000.f;
            final String lineString = dataString.substring(firstPos + 1, lastPos);
            //Log.d(TAG, "data: " + dataString);

            // Is it a plotter labels line? A labels line is of the form "Labels:Label0,Label1,Label2"
            final String LABELS_LINE_START = "Labels:";
            if (lineString.startsWith(LABELS_LINE_START)) {
                // Set the labels in the selection menu
                final String labels = lineString.substring(LABELS_LINE_START.length());
                int j = 0;
                for (String label : labels.split("[,; \t]")) {
                    if (j < mSelectSubmenu.size()) {
                        final MenuItem item = mSelectSubmenu.getItem(j);
                        getActivity().runOnUiThread(() -> item.setTitle(label));
                    }
                    ++j;
                }
            } else {
                // Add the float values
                int j = 0;
                for (String valueString : lineString.split("[,; \t]")) {
                    try {
                        final float value = Float.parseFloat(valueString);
                        if (peripheralIdentifier != null) {
                            //Log.d(TAG, "value " + j + ": (" + currentTimestamp + ", " + value + ")");
                            //Log.d(TAG, "value " + j + ": " + value);
                            addEntry(peripheralIdentifier, j++, value, currentTimestamp);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }

                mMainHandler.post(this::notifyDataSetChanged);
            }

            mUartDataManager.removeRxCacheFirst(lastPos, peripheralIdentifier);
        }

    }

    // endregion
}
