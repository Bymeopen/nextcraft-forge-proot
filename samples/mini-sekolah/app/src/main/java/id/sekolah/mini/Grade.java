package id.sekolah.mini;

/** One score of one student in one subject. */
final class Grade {
    final long id;
    final String subject;
    final int score;

    Grade(long id, String subject, int score) {
        this.id = id;
        this.subject = subject;
        this.score = score;
    }
}
