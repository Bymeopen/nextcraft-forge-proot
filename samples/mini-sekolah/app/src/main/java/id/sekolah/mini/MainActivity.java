package id.sekolah.mini;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteConstraintException;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;

/** Student list with search, add, edit and delete. Tapping a student opens their grades. */
public class MainActivity extends Activity {
    private Db db;
    private StudentAdapter adapter;
    private EditText search;
    private TextView summary;
    private TextView empty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        db = new Db(this);
        search = findViewById(R.id.search);
        summary = findViewById(R.id.summary);
        empty = findViewById(R.id.empty);

        ListView list = findViewById(R.id.list);
        adapter = new StudentAdapter(this);
        list.setAdapter(adapter);
        list.setEmptyView(empty);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Intent open = new Intent(this, StudentActivity.class);
            open.putExtra(StudentActivity.EXTRA_ID, adapter.getItem(position).id);
            startActivity(open);
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showActions(adapter.getItem(position));
            return true;
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                refresh();
            }
        });
        findViewById(R.id.add).setOnClickListener(v -> showStudentDialog(null));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.seed_demo);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            db.seedDemo();
            refresh();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void refresh() {
        String query = search.getText().toString();
        adapter.clear();
        adapter.addAll(db.students(query));
        adapter.notifyDataSetChanged();
        empty.setText(query.trim().isEmpty() ? R.string.empty_students : R.string.empty_search);
        int count = db.studentCount();
        summary.setText(count == 0
                ? getString(R.string.summary_none)
                : getString(R.string.summary, count, Format.average(db.overallAverage())));
    }

    private void showActions(final Student student) {
        new AlertDialog.Builder(this)
                .setTitle(student.name)
                .setItems(new CharSequence[] {getString(R.string.edit), getString(R.string.delete)}, (dialog, which) -> {
                    if (which == 0) showStudentDialog(student);
                    else confirmDelete(student);
                })
                .show();
    }

    private void confirmDelete(final Student student) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_student)
                .setMessage(getString(R.string.delete_student_message, student.name))
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    db.deleteStudent(student.id);
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Add form when [existing] is null, edit form otherwise. Stays open until the input is valid. */
    private void showStudentDialog(final Student existing) {
        View form = getLayoutInflater().inflate(R.layout.dialog_student, null);
        final EditText nis = form.findViewById(R.id.nis);
        final EditText name = form.findViewById(R.id.name);
        final EditText kelas = form.findViewById(R.id.kelas);
        if (existing != null) {
            nis.setText(existing.nis);
            name.setText(existing.name);
            kelas.setText(existing.kelas);
        }
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? R.string.add_student : R.string.edit_student)
                .setView(form)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String nisText = nis.getText().toString().trim();
            String nameText = name.getText().toString().trim();
            String kelasText = kelas.getText().toString().trim();
            boolean valid = true;
            if (nisText.isEmpty()) {
                nis.setError(getString(R.string.required));
                valid = false;
            }
            if (nameText.isEmpty()) {
                name.setError(getString(R.string.required));
                valid = false;
            }
            if (kelasText.isEmpty()) {
                kelas.setError(getString(R.string.required));
                valid = false;
            }
            if (!valid) return;
            try {
                if (existing == null) db.addStudent(nisText, nameText, kelasText);
                else db.updateStudent(existing.id, nisText, nameText, kelasText);
                dialog.dismiss();
                refresh();
            } catch (SQLiteConstraintException e) {
                nis.setError(getString(R.string.nis_taken));
            }
        });
    }

    private static final class StudentAdapter extends ArrayAdapter<Student> {
        StudentAdapter(Context context) {
            super(context, 0, new ArrayList<Student>());
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null
                    ? convertView
                    : LayoutInflater.from(getContext()).inflate(R.layout.item_student, parent, false);
            Student student = getItem(position);
            ((TextView) row.findViewById(R.id.name)).setText(student.name);
            ((TextView) row.findViewById(R.id.meta)).setText(
                    getContext().getString(R.string.student_meta, student.nis, student.kelas));
            TextView average = row.findViewById(R.id.average);
            average.setText(Format.average(student.average));
            average.setTextColor(Format.scoreColor(getContext(), student.average));
            return row;
        }
    }
}
