import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

// Run using Java source-file mode. Read the pattern used by the actual source.
class TurkishDateCheck {
    public static void main(String[] args) throws Exception {
        var file = Path.of("keiyoushi-extensions-tr/src/tr/tortugaceviri/src/eu/kanade/tachiyomi/extension/tr/tortugaceviri/TortugaCeviri.kt");
        var text = Files.readString(file);
        var matcher = Pattern.compile("ofPattern\\(\"([^\"]+)\", Locale\\.forLanguageTag\\(\"tr\"\\)\\)").matcher(text);
        if (!matcher.find()) throw new AssertionError("Turkish date formatter not found in source");
        var formatter = DateTimeFormatter.ofPattern(matcher.group(1), Locale.forLanguageTag("tr"));
        var actual = LocalDate.parse("28 Haziran 2026", formatter);
        if (!actual.equals(LocalDate.of(2026, 6, 28))) throw new AssertionError(actual);
        System.out.println("Turkish chapter date: " + actual);
    }
}
