package dev.idreader.capacitor;
import java.util.Arrays;
public class MrzLinesTest {
    public static void main(String[] args) throws Exception {
        java.nio.file.Path fixtures = java.nio.file.Paths.get("ios/Tests/IdReaderPluginTests/mrz-fixtures.tsv");
        for (String row : java.nio.file.Files.readAllLines(fixtures)) {
            String[] fields = row.split("\\t", -1);
            boolean actual = MrzLines.find(Arrays.asList(fields[2], fields[3])) != null;
            if (actual != fields[1].equals("valid")) throw new AssertionError(fields[0]);
        }
        String td1a = "I<UTOD231458907<<<<<<<<<<<<<<<";
        String td1b = "7408122F1204159UTO<<<<<<<<<<<6";
        String td3a = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<";
        String td3b = "L898902C36UTO7408122F1204159ZE184226B<<<<<10";
        if (MrzLines.find(Arrays.asList(td1a, td1b)) == null) throw new AssertionError("TD1 not found");
        if (MrzLines.find(Arrays.asList(td3a, td3b)) == null) throw new AssertionError("TD3 not found");
        if (MrzLines.find(Arrays.asList(td3a, td3b.replace("7408122", "74O8122"))) == null) throw new AssertionError("Numeric OCR correction failed");
        if (MrzLines.find(Arrays.asList(td3a, td3b.replace("L898", "L899"))) != null) throw new AssertionError("Invalid check accepted");
        if (MrzLines.find(Arrays.asList(td3a, "<<<<<<<<<0UTO7408122F1204159ZE184226B<<<<<10")) != null) throw new AssertionError("Empty number accepted");
        if (MrzLines.find(Arrays.asList("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", td3b)) != null) throw new AssertionError("Invalid header accepted");
        if (MrzLines.find(Arrays.asList("FACE", "NOT A DOCUMENT")) != null) throw new AssertionError("Non-MRZ accepted");
    }
}
