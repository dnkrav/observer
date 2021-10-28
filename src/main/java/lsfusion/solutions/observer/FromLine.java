package lsfusion.solutions.observer;

import lsfusion.server.physics.admin.log.ServerLoggers;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// From line specification regarding Appendix A of the RFC 4155
// https://datatracker.ietf.org/doc/html/rfc4155
// - the exact character sequence of "From";
// - a single Space character (0x20);
// - "addr-spec" address conformant with the syntax from RFC 2822;
// - a single Space character (0x20);
// - a timestamp indicating the UTC date and time when the message was originally received,
//   conformant with the syntax of the traditional UNIX 'ctime' output sans timezone;
// - an end-of-line marker.

// The following implementation of "addr-spec" address is realised from the section 3.4 of the RFC 2822
//  addr-spec               =       local-part "@" domain
//      local-part          =       dot-atom                            ; ATTENTION: realized here
//                                  / quoted-string / obs-local-part    ; ATTENTION: not realized here
//          dot-atom        =       contains no characters other than atext characters
//                                  or "." surrounded by atext characters
//      domain              =       dot-atom / domain-literal / obs-domain
//          domain-literal  =       [CFWS] "[" *([FWS] dcontent) [FWS] "]" [CFWS]
//          dcontent        =       dtext / quoted-pair
//              dtext       =       NO-WS-CTL /     ; Non white space controls
//                                  %d33-90 /       ; The rest of the US-ASCII
//                                  %d94-126        ;  characters not including "[",
//                                                  ;  "]", or "\"

// Specification for the atext characters
//  atext       =       ALPHA / DIGIT / ; Any character except controls,
//                      "!" / "#" /     ;  SP, and specials.
//                      "$" / "%" /     ;  Used for atoms
//                      "&" / "'" /
//                      "*" / "+" /
//                      "-" / "/" /
//                      "=" / "?" /
//                      "^" / "_" /
//                      "`" / "{" /
//                      "|" / "}" /
//                      "~"
//
//      ALPHA   =       %x41-5A / %x61-7A   ; A-Z / a-z
//      DIGIT   =       %x30-39  ; 0-9

public class FromLine {
    public String line;
    public transient String addr;
    public transient String date;

    public transient boolean isFromLine;
    public transient boolean confidence;

    public FromLine() {
        isFromLine = false;
        confidence = true;
    }

    public boolean checkFromLine(String line)
            throws IOException {
        if (line == null) {
            return isFromLine;
        } else {
            this.line = line;
        }

        try {
            String atextExpression = "[[a-zA-Z][0-9][+-][\\!\\#\\$\\%\\&\\'\\*\\/\\=\\?\\^\\_\\`\\{\\|\\}\\~]]*";
            String dotAtomExpression = atextExpression + "\\.?" + atextExpression;

            // Pick the only implementation via dot-atom
            String localPartExpression = dotAtomExpression;
            String domainExpression = dotAtomExpression;

            String addrSpecExpression = "(" + localPartExpression + "@" + domainExpression + "|MAILER-DAEMON)";

            String timestampExpression = "([a-zA-Z]{3}\\s[a-zA-Z]{3}\\s\\d{2}\\s\\d{2}:\\d{2}:\\d{2}\\s[+-]\\d{4}\\s\\d{4})";

            String fromLineExpression = "From\\s" + addrSpecExpression + "\\s" + timestampExpression;

            Pattern fromLineRegex = Pattern.compile(fromLineExpression);
            // Confidence check is added for reqular expression debugging
            Pattern candidateRegex = Pattern.compile("From\\s(.*[@|MAILER\\-DAEMON].*)\\s(.*)");

            Matcher candidateMatch = candidateRegex.matcher(line);

            if (candidateMatch.matches()) {
                Matcher fromLineMatch = fromLineRegex.matcher(line);
                if (fromLineMatch.matches()) {
                    isFromLine = true;
                    confidence = true;
                    addr = fromLineMatch.group(1);
                    date = fromLineMatch.group(2);
                } else {
                    isFromLine = false;
                    confidence = false;
                }
            } else {
                isFromLine = false;
                confidence = true;
            }
        } catch (OutOfMemoryError e) {
            // Monitor the memory overfilling
            ServerLoggers.importLogger.error("FromLine is too large for JVM memory of " +
                    Runtime.getRuntime().maxMemory() + ". Occurred from " + line);
            throw new IOException("FromLine is too large: " + line);
        }
        return isFromLine;
    }

    public String fromLine() {
        return String.format("From %s %s",addr, date);
    }

    public LocalDateTime datetime() {
        return LocalDateTime.parse(date);
    }
}

// Regular Expression test
//  Regex Editor at https://regex101.com/
//      Flavor: Java 8
//      Function: match
/* Test String for envaddress
12+3@abc
45=6@cab.org
12hf7'^8@dab
aks690q/?ke23@dac.com
13g28.1`5{6}28@cbaMAILER-DAEMON
982~746.alsk|diw2l@bdc.us
MAILER-DAEMON
 */
/* Test String for UTC timestamp
Fri Dec 13 22:18:64 +0003 2013
Tue Jun 14 16:34:33 -0002 2011
 */