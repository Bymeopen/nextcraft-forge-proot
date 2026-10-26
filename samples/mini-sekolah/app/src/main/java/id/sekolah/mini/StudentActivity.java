package id.sekolah.mini;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

/** One student: their details, the average, and the list of grades with add and delete. */
public class StudentActivity extends Activity {
    static final String EXTRA_ID = "student_id";

    private Db db;
    private long studentId;
    private GradeAdapter adapter;
    private TextView title;
    private TextView meta;
    private TextView average;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_student);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        db = new Db(this);
        studentId = getIntent().getLongExtra(EXTRA_ID, -1L);
        title = findViewById(R.id.title);
        meta = findViewById(R.id.meta);
        average = findViewById(R.id.average);

        ListView list = findViewById(R.id.list);
        adapter = new GradeAdapter(this);
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.empty));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            confirmDelete(adapter.getItem(position));
            return true;
        });
        findViewById(R.id.add).setOnClickListener(v -> showGradeDialog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void refresh() {
        Student student = db.student(studentId);
        if (student == null) {
            // Deleted elsewhere: nothing to show.
            Toast.makeText(this, R.string.student_missing, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        setTitle(student.name);
        title.setText(student.name);
        meta.setText(getString(R.string.student_meta, student.nis, student.kelas));
        if (student.gradeCount == 0) {
            average.setText(R.string.no_average);
        } else {
            average.setText(getString(R.string.average_label, Format.average(student.average)));
        }
        average.setTextColor(Format.scoreColor(this, student.average));
        adapter.clear();
        adapter.addAll(db.grades(studentId));
        adapter.notifyDataSetChanged();
    }

    private void confirmDelete(final Grade grade) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_grade)
                .setMessage(getString(R.string.delete_grade_message, grade.subject))
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    db.deleteGrade(grade.id);
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showGradeDialog() {
        View form = getLayoutInflater().inflate(R.layout.dialog_grade, null);
        final EditText subject = form.findViewById(R.id.subject);
        final EditText score = form.findViewById(R.id.score);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.add_grade)
                .setView(form)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String subjectText = subject.getText().toString().trim();
            boolean valid = true;
            if (subjectText.isEmpty()) {
                subject.setError(getString(R.string.required));
                valid = false;
            }
            int value = -1;
            try {
                value = Integer.parseInt(score.getText().toString().trim());
            } catch (NumberFormatException ignored) {
                // Falls through to the range message below.
            }
            if (value < 0 || value > 100) {
                score.setError(getString(R.string.score_range));
                valid = false;
            }
            if (!valid) return;
            db.addGrade(studentId, subjectText, value);
            dialog.dismiss();
            refresh();
        });
    }

    private static final class GradeAdapter extends ArrayAdapter<Grade> {
        GradeAdapter(Context context) {
            super(context, 0, new ArrayList<Grade>());
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null
                    ? convertView
                    : LayoutInflater.from(getContext()).inflate(R.layout.item_grade, parent, false);
            Grade grade = getItem(position);
            ((TextView) row.findViewById(R.id.subject)).setText(grade.subject);
            TextView score = row.findViewById(R.id.score);
            score.setText(String.valueOf(grade.score));
            score.setTextColor(Format.scoreColor(getContext(), grade.score));
            return row;
        }
    }
}
