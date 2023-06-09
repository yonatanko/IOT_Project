package com.example.tutorial6;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.text.method.ScrollingMovementMethod;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.fragment.app.Fragment;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.opencsv.CSVWriter;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;


public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    private enum Connected { False, Pending, True }

    private String deviceAddress;
    private SerialService service;

    private TextView receiveText;
    private TextView sendText;
    private TextUtil.HexWatcher hexWatcher;

    private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean hexEnabled = false;
    private boolean pendingNewline = false;
    private String newline = TextUtil.newline_crlf;

    ArrayList<ILineDataSet> dataSets = new ArrayList<>();

    LineChart mpLineChart;
    LineData data;
    LineDataSet N_lineDataSet;

    Boolean recording = false;
    Boolean is_first_start = false;
    Boolean is_first_reset = false;
    Boolean reset = false;
    Boolean saving = false;
    Boolean stopped = false;

    float t0 = 0.0f;
    float plot_t0 = 0.0f;

    ArrayList<Float> n_value_list = new ArrayList<>();

    int elements_to_avg = 10;
    int elements_to_remove = 4;

    ArrayList<String[]> rowsContainer = new ArrayList<>();
    float threshold = 10.1f;
    int num_of_steps = 0;

    float last_ten_N_avg = 0.0f;

    static String nameValue;
    String stepsValue;
    String activityValue;
    RadioButton selectedBtn;

    Python py =  Python.getInstance();
    PyObject pyobj = py.getModule("test");


    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
        deviceAddress = getArguments().getString("device");

    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if(service != null)
            service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    @Override
    public void onStop() {
        if(service != null && !getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation") // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        if(initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if(initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        mpLineChart = (LineChart) view.findViewById(R.id.line_chart);

        N_lineDataSet =  new LineDataSet(emptyDataValues(), "N");
        dataSets.add(N_lineDataSet);
        N_lineDataSet.setLineWidth(1.5f);
        N_lineDataSet.setColor(Color.RED);
        N_lineDataSet.setCircleColor(Color.RED);

        data = new LineData(dataSets);
        mpLineChart.setData(data);
        mpLineChart.invalidate();

        Button buttonCsvShow = (Button) view.findViewById(R.id.button2);
        Button buttonStart = (Button) view.findViewById(R.id.StartButton);
        Button buttonStop = (Button) view.findViewById(R.id.StopButton);
        Button buttonReset = (Button) view.findViewById(R.id.ResetButton);
        Button buttonSave = (Button) view.findViewById(R.id.SaveButton);

        final EditText fileName = view.findViewById(R.id.EditFileName);
        final EditText numSteps = view.findViewById(R.id.EditNumberOfSteps);
        final RadioGroup activityType = view.findViewById(R.id.RadioGroup);

        buttonCsvShow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OpenLoadCSV();
            }
        });

        buttonStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!recording) {
                    recording = true;
                    is_first_start = true;
                    reset = false;
                    stopped = false;
                    num_of_steps = 0;
                    Toast.makeText(getActivity(), "Started recording", Toast.LENGTH_SHORT).show();
                }
                else{
                    Toast.makeText(getActivity(), "Can not start another record while recording!", Toast.LENGTH_SHORT).show();
                }
            }
        });

        buttonStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (recording) {
                    recording = false;
                    stopped = true;
                    Toast.makeText(getActivity(), "Stopped recording", Toast.LENGTH_SHORT).show();
                }
                else{
                    Toast.makeText(getActivity(), "Stopped without starting!", Toast.LENGTH_SHORT).show();
                }
            }
        });

        buttonSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (stopped){
                    saving = true;
                    nameValue = fileName.getText().toString();
                    stepsValue = numSteps.getText().toString();
                    int selectedRadioBtnID = activityType.getCheckedRadioButtonId();
                    if (selectedRadioBtnID != -1) {
                        selectedBtn = view.findViewById(selectedRadioBtnID);
                        activityValue = selectedBtn.getText().toString();
                    }

                    reset = true;
                    is_first_reset = true;

                    mpLineChart.clear();
                    data.clearValues();
                    mpLineChart = (LineChart) view.findViewById(R.id.line_chart);

                    N_lineDataSet = new LineDataSet(emptyDataValues(), "N");
                    dataSets.add(N_lineDataSet);
                    N_lineDataSet.setLineWidth(1.5f);
                    N_lineDataSet.setColor(Color.RED);
                    N_lineDataSet.setCircleColor(Color.RED);

                    data = new LineData(dataSets);
                    mpLineChart.setData(data);
                    mpLineChart.invalidate();

                }
                else{
                    Toast.makeText(getActivity(), "Can not save before stopping!", Toast.LENGTH_SHORT).show();
                }
            }
        });

        buttonReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!recording) {
                    reset = true;
                    is_first_reset = true;
                    rowsContainer = new ArrayList<>();
                    num_of_steps = 0;
                    Toast.makeText(getActivity(), "Reset done", Toast.LENGTH_SHORT).show();

                    mpLineChart.clear();
                    data.clearValues();
                    mpLineChart = (LineChart) view.findViewById(R.id.line_chart);

                    N_lineDataSet = new LineDataSet(emptyDataValues(), "N");
                    dataSets.add(N_lineDataSet);
                    N_lineDataSet.setLineWidth(1.5f);
                    N_lineDataSet.setColor(Color.RED);
                    N_lineDataSet.setCircleColor(Color.RED);

                    data = new LineData(dataSets);
                    mpLineChart.setData(data);
                    mpLineChart.invalidate();
                }
                else{
                    Toast.makeText(getActivity(), "Can not reset while recording!", Toast.LENGTH_SHORT).show();
                }
            }
        });

        return view;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
        menu.findItem(R.id.hex).setChecked(hexEnabled);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if (id == R.id.newline) {
            String[] newlineNames = getResources().getStringArray(R.array.newline_names);
            String[] newlineValues = getResources().getStringArray(R.array.newline_values);
            int pos = java.util.Arrays.asList(newlineValues).indexOf(newline);
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Newline");
            builder.setSingleChoiceItems(newlineNames, pos, (dialog, item1) -> {
                newline = newlineValues[item1];
                dialog.dismiss();
            });
            builder.create().show();
            return true;
        } else if (id == R.id.hex) {
            hexEnabled = !hexEnabled;
            sendText.setText("");
            hexWatcher.enable(hexEnabled);
            sendText.setHint(hexEnabled ? "HEX mode" : "");
            item.setChecked(hexEnabled);
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /*
     * Serial + UI
     */
    private String[] clean_str(String[] stringsArr){
        for (int i = 0; i < stringsArr.length; i++)  {
            stringsArr[i]=stringsArr[i].replaceAll(" ","");
        }

        return stringsArr;
    }

    private void connect() {
        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
//            status("connecting...");
            connected = Connected.Pending;
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), device);
            service.connect(socket);
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    private void disconnect() {
        connected = Connected.False;
        service.disconnect();
    }

    private void send(String str) {
        if(connected != Connected.True) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String msg;
            byte[] data;
            if(hexEnabled) {
                StringBuilder sb = new StringBuilder();
                TextUtil.toHexString(sb, TextUtil.fromHexString(str));
                TextUtil.toHexString(sb, newline.getBytes());
                msg = sb.toString();
                data = TextUtil.fromHexString(msg);
            } else {
                msg = str;
                data = (str + newline).getBytes();
            }
            SpannableStringBuilder spn = new SpannableStringBuilder(msg + '\n');
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            service.write(data);
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }

    private void writeToCsv(ArrayList<String[]> rowsContainer, CSVWriter csvWriter){
        for (int i = 0; i < rowsContainer.size(); i++){
            csvWriter.writeNext(rowsContainer.get(i));
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void receive(byte[] message) {

        if(hexEnabled) {
//            receiveText.append(TextUtil.toHexString(message) + '\n');
        } else {
            String msg = new String(message);
            if(newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {
                String msg_to_save = msg;
                msg_to_save = msg.replace(TextUtil.newline_crlf, TextUtil.emptyString);
                if (msg_to_save.length() > 1){
                    String[] parts = msg_to_save.split(",");
                    parts = clean_str(parts);
                    parts[3] = String.valueOf(Integer.parseInt(parts[3])/1000.0);

                    // Check if the message has four parts (three floats and one integer)

                    try {
                        File file = new File("/sdcard/csv_dir/");
                        file.mkdirs();

                        float x = Float.parseFloat(parts[0]);
                        float y = Float.parseFloat(parts[1]);
                        float z = Float.parseFloat(parts[2]);
                        PyObject obj = pyobj.callAttr("main", x,y,z);
                        float n_value = obj.toFloat();
                        n_value_list.add(n_value);
                        Log.d("size", String.valueOf(n_value_list.size()));


                        // Parse the string values: parts[0], parts[1], parts[2] are floats, and parts[3] is an integer
                        if (recording) {
                            float latest_t =  Float.parseFloat(parts[3]);
                            if (is_first_start)
                            {
                                t0 = latest_t;
                                is_first_start = false;
                            }
                            float actual_t = latest_t - t0;
                            String row[] = new String[]{String.valueOf(actual_t), parts[0], parts[1], parts[2]};
                            rowsContainer.add(row);
                        }

                        if (reset) {
                            if (is_first_reset)
                            {
                                plot_t0 = Float.parseFloat(parts[3]);
                                is_first_reset = false;
                            }
                            if (!saving) {num_of_steps = 0;}
                            reset = false;
                        }

                        if (saving) {
                            if (nameValue.length() > 0 && activityValue != null && stepsValue.length() > 0) {
                                String currentCsv = "/sdcard/csv_dir/" + nameValue + ".csv";
                                CSVWriter csvWriter = new CSVWriter(new FileWriter(currentCsv,true));
                                String name_row[] = new String[]{"NAME:", nameValue + ".csv", "", ""};
                                csvWriter.writeNext(name_row);
                                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mm");
                                String date = LocalDateTime.now().format(formatter);
                                String date_row[] = new String[]{"EXPERIMENT TIME:", date, "", ""};
                                csvWriter.writeNext(date_row);
                                String activity_row[] = new String[]{"ACTIVITY TYPE:", activityValue, "", ""};
                                csvWriter.writeNext(activity_row);
                                String steps_row[] = new String[]{"COUNT OF ACTUAL STEPS: ", stepsValue, "", ""};
                                csvWriter.writeNext(steps_row);
                                String estimates_steps_row[] = new String[]{"ESTIMATED NUMBER OF STEPS: ", String.valueOf(num_of_steps), "", ""};
                                csvWriter.writeNext(estimates_steps_row);
                                String empty_row[] = new String[]{"", "", "", ""};
                                csvWriter.writeNext(empty_row);
                                String header_row[] = new String[]{"Time [sec]", "ACC X", "ACC Y", "ACC Z"};
                                csvWriter.writeNext(header_row);
                                writeToCsv(rowsContainer, csvWriter);
                                csvWriter.close();
                                Toast.makeText(getActivity(), "Saved the record", Toast.LENGTH_SHORT).show();

                                num_of_steps = 0;
                            }
                            else {
                                Toast.makeText(getActivity(), "Haven't filled out all the inputs! Record failed!", Toast.LENGTH_SHORT).show();
                            }
                                rowsContainer = new ArrayList<String[]>();
                                saving = false;
                        }
                        if (n_value_list.size() == elements_to_avg) {
                            float sum = 0.0f;
                            for(int i = 0; i < elements_to_avg; i++)
                                sum += n_value_list.get(i);
                            last_ten_N_avg = sum / elements_to_avg;
                            Log.d("avg is", String.valueOf(last_ten_N_avg));

                            if (last_ten_N_avg > threshold) {
                                num_of_steps++;
                            }
                            n_value_list.subList(0, elements_to_remove).clear();
                            Log.d("size", String.valueOf(n_value_list.size()));
                            data.addEntry(new Entry(Float.parseFloat(parts[3]) - plot_t0, last_ten_N_avg), 0);
                            N_lineDataSet.notifyDataSetChanged(); // let the data know a dataSet changed
                            mpLineChart.notifyDataSetChanged(); // let the chart know it's data changed
                            mpLineChart.invalidate(); // refresh
                        }

                        String text_to_show = "Number of steps recorded: " + String.valueOf(num_of_steps);
                        receiveText.setText(text_to_show);

                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                }
            }
        }
    }


    private void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect() {
//        status("connected");
        connected = Connected.True;
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage());
        disconnect();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void onSerialRead(byte[] data) {
        try {
        receive(data);}
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage());
        disconnect();
    }

    private ArrayList<Entry> emptyDataValues()
    {
        ArrayList<Entry> dataVals = new ArrayList<Entry>();
        return dataVals;
    }

    private void OpenLoadCSV(){
        Intent intent = new Intent(getContext(),LoadCSV.class);
        startActivity(intent);
    }

}
