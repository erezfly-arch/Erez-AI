# Erez AI — English-first, Hebrew-aware Android assistant

Native Android project in Kotlin. iPhone development is paused until Android is complete.

## Language behavior
- English is the assistant's default working and response language.
- The assistant is designed to understand both English and Hebrew, including colloquial Israeli Hebrew.
- Spoken replies switch to Hebrew TTS when the generated answer contains Hebrew; otherwise English TTS is selected.
- The voice wake phrase is **“Hey Erez”** (with common English variants); Hebrew wake aliases remain available.
- Speech recognition uses the multilingual Whisper model in automatic language-detection mode. Actual language detection and wake-word accuracy still require testing on the target phone.

## עקרונות עלות ופרטיות
- אין שימוש ב-API בתשלום, מפתח API בתשלום, מנוי או חיוב לפי פעולה.
- מודל השפה והראייה רץ מקומית על המכשיר דרך LiteRT-LM.
- זיהוי הדיבור בעברית רץ מקומית דרך Whisper Base + sherpa-onnx.
- לאחר הורדת המודלים הראשונית, שיחה, זיהוי דיבור וניתוח תמונות אינם תלויים במכסת שימוש של ספק AI.
- חיפוש רשת משתמש בדף HTML ציבורי של DuckDuckGo ללא מפתח API; ספק החיפוש עדיין עשוי להפעיל הגנות אנטי-בוט רגילות.
- הורדות ראשוניות דורשות אינטרנט. יש צורך בכ־2.2GB פנויים למודל הראייה והשפה ועוד כ־160MB למודל הדיבור, בנוסף למקום זמני.

## רכיבים שממומשים בקוד
- אפליקציית Android בעברית, ממשק פקודות ועוזר צף.
- מודל Qwen2-VL 2B מקומי לשיחה ולהבנת תמונות דרך LiteRT-LM. ההורדה ניתנת לחידוש ומאומתת באמצעות SHA-256.
- דגימת מסך דרך MediaProjection בהסכמה מפורשת, עם שמירת עד שישה פריימים אחרונים לניתוח רצף קצר של וידאו.
- זיהוי טקסט באנגלית עם ML Kit, זיהוי עברית עם Tesseract מקומי, ותוויות חזותיות כלליות.
- זיהוי דיבור מקומי רב-לשוני עם Whisper Base multilingual דרך sherpa-onnx, במצב זיהוי שפה אוטומטי. ההאזנה פועלת בשירות foreground עם התראה וכפתור עצירה. פקודות קוליות דורשות פתיחה במילת ההפעלה "ארז" כדי לא לפרש דיבור מסרטונים כפקודות.
- השהיית המיקרופון בזמן שהעוזר מדבר כדי לצמצם הד.
- חיפוש רשת ללא מפתח API והעברת תוצאות למודל המקומי לסיכום עם קישורים.
- מסך אישור לפני פתיחת פעולות תקשורת ויומן: WhatsApp, דוא"ל, חיוג ואירועים. WhatsApp ודוא"ל עדיין דורשים את אישור השליחה הסופי באפליקציה שלהם; חיוג נפתח בחייגן; אירוע נפתח ביומן.
- ניסוח תגובות בעזרת המודל המקומי והעתקת הטיוטה ללוח לאחר אישור.
- שירות נגישות אופציונלי לקריאת טקסט גלוי, רק לאחר שהמשתמש מפעיל אותו ידנית בהגדרות Android.
- GitHub Actions בונה APK Debug בכל push ל-main.

## מגבלות שעדיין דורשות אימות במכשיר
- בניית APK מוצלחת מאמתת קומפילציה בלבד, לא ביצוע תקין במכשיר.
- מודל Qwen2-VL גדול; מהירות, צריכת RAM, חום ויכולת הרצה תלויים בדגם הטלפון ובזיכרון הפנוי.
- Whisper Base עשוי להיות איטי במכשירים חלשים. איכות עברית תלויה במיקרופון, רעשי רקע והגייה.
- ניתוח וידאו מבוסס עד שישה פריימים עוקבים שנדגמים בערך פעם ב־1.2 שניות, ולא הבנה רציפה של כל פריים.
- שמע של סרטון נקלט דרך המיקרופון רק אם הרמקול משמיע אותו ונגיש למיקרופון. Android ואפליקציות מסוימות חוסמים לכידת שמע פנימי; אין הבטחה לשמוע אודיו דרך אוזניות או תוכן מוגן.
- פעולות WhatsApp/דוא"ל/יומן/חיוג נפתחות עם פרטים מוכנים, אך האישור הסופי נשאר באפליקציית היעד. תגובות מועתקות ללוח ולא מתפרסמות אוטומטית.
- חיפוש רשת תלוי בזמינות ובמדיניות של אתר החיפוש החינמי.

## בנייה
GitHub Actions: `.github/workflows/build-apk.yml`
הפקודה: `gradle --no-daemon assembleDebug`
Artifact: `Erez-AI-debug-APK`

## מודלים וספריות
- LiteRT-LM + Qwen2-VL 2B — מודל מקומי להסקה חזותית ושפתית.
- sherpa-onnx + Whisper Base multilingual — זיהוי דיבור מקומי.
- Tesseract4Android עם `heb.traineddata` — OCR עברי מקומי.
- Google ML Kit — OCR לטיני ותוויות תמונה.
- Jsoup — קריאת תוצאות HTML של חיפוש ציבורי.

הפרויקט אינו מוצג כמוצר גמור עד שבניית ה-APK עוברת ונבדקות בפועל יכולות הליבה במכשיר Android היעד.
