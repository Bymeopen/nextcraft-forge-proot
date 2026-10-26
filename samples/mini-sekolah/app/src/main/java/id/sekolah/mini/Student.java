package id.sekolah.mini;

/** One row of the students table plus the figures computed from that student's grades. */
final class Student {
    final long id;
    final String nis;
    final String name;
    final String kelas;
    /** Mean score, or NaN when the student has no grades yet. */
    final double average;
    final int gradeCount;

    Student(long id, String nis, String name, String kelas, double average, int gradeCount) {
        this.id = id;
        this.nis = nis;
        this.name = name;
        this.kelas = kelas;
        this.average = average;
        this.gradeCount = gradeCount;
    }
}
