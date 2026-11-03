package com.ameer.uyirmesh.ui

/** Basic first-aid steps (standard Red Cross / WHO guidance), in English and Tamil. Works offline. */
object FirstAid {
    fun topics(lang: String): List<Pair<String, List<String>>> = if (lang == "ta") TA else EN

    private val EN = listOf(
        "Not breathing - CPR" to listOf(
            "Make sure the place is safe. Tap the person and shout. Call for help (108 / 112) if there is signal.",
            "If they are not breathing normally, lay them on their back on a firm surface.",
            "Put the heel of one hand in the centre of the chest, the other hand on top. Keep arms straight.",
            "Push hard and fast: about 5 cm deep, 100-120 times a minute. Let the chest rise fully between pushes.",
            "If you are trained, give 2 rescue breaths after every 30 pushes. If not, keep pushing without stopping.",
            "Do not stop until the person breathes, help takes over, or you are too exhausted to continue.",
        ),
        "Heavy bleeding" to listOf(
            "Press firmly on the wound with a clean cloth or your hand (use a plastic bag as a glove if you can).",
            "Keep pressing for at least 10 minutes. Do not keep lifting the cloth to look.",
            "If blood soaks through, put another cloth on top and keep pressing.",
            "If possible, raise the injured part above the heart (not if a bone may be broken).",
            "A tight band (tourniquet) above the wound only for life-threatening limb bleeding that pressure cannot stop. Note the time.",
            "Keep the person warm and lying down. Watch for pale, cold, sweaty skin (shock).",
        ),
        "Burns" to listOf(
            "Stop the burning: move away from the heat, put out flames (stop, drop and roll).",
            "Cool the burn under clean running water for 20 minutes.",
            "Remove rings, watches and tight clothing near the burn, unless stuck to the skin.",
            "Do NOT put ice, butter, oil, toothpaste or ointment on the burn. Do not burst blisters.",
            "Cover loosely with clean plastic wrap or a clean, dry cloth.",
            "Get medical help for large burns, burns on face, hands or private parts, and all burns in children.",
        ),
        "Drowning" to listOf(
            "Do not jump in unless trained. Throw a rope, tube, bottle or anything that floats.",
            "Once out of the water, check breathing.",
            "If not breathing, start CPR. If trained, give 5 rescue breaths first.",
            "If breathing, turn them on their side (recovery position) so water can drain.",
            "Remove wet clothes and keep them warm. Everyone who nearly drowned should see a doctor.",
        ),
        "Broken bone" to listOf(
            "Do not try to straighten the bone. Keep the injured part still.",
            "Support it with a splint: a stick, rolled newspaper or cardboard tied on both sides, with padding.",
            "Tie above and below the injury, not on it. Check fingers or toes stay warm and pink.",
            "For an open wound, cover it with a clean cloth first. Do not push the bone back in.",
            "Do not move a person with a neck or back injury unless they are in danger.",
        ),
        "Snake bite" to listOf(
            "Move away from the snake. Keep the person calm and as still as possible.",
            "Remove rings, watches and tight things near the bite.",
            "Keep the bitten limb still with a splint, at the level of the heart.",
            "Do NOT cut the wound, suck the venom, tie a tight band, or put ice or herbs on it.",
            "Get to a hospital quickly - anti-snake venom is the only cure. Note the time of the bite.",
        ),
        "Electric shock" to listOf(
            "Do not touch the person while they are still touching the power source.",
            "Switch off the power if you can, or push them away with dry wood or plastic while standing on something dry.",
            "Stay away from fallen power lines and flood water near electric lines.",
            "Check breathing. Start CPR if they are not breathing.",
            "Cool any burns with water and get medical help.",
        ),
        "Heat stroke" to listOf(
            "Signs: very hot skin, confusion, headache, fainting, no sweating.",
            "Move the person to shade or a cool place.",
            "Cool them fast: wet the skin with water and fan them; put wet cloths on neck, armpits and groin.",
            "Give small sips of water if they are awake and can swallow.",
            "Get medical help urgently.",
        ),
        "Too cold (hypothermia)" to listOf(
            "Common after long time in flood water. Signs: shivering, slow speech, confusion.",
            "Move the person to a dry, sheltered place. Remove wet clothes gently.",
            "Wrap them in dry blankets or clothes, cover the head. Warm the body centre first.",
            "Give warm sweet drinks if they are awake. No alcohol.",
            "Do not rub the arms and legs hard. Get medical help.",
        ),
        "Choking (adult)" to listOf(
            "If they can cough, tell them to keep coughing.",
            "If they cannot breathe, speak or cough: give up to 5 firm back blows between the shoulder blades.",
            "Then give up to 5 abdominal thrusts: stand behind, fist above the navel, pull sharply in and up.",
            "Repeat 5 back blows and 5 thrusts until the object comes out.",
            "If they become unconscious, start CPR.",
        ),
        "Unconscious but breathing" to listOf(
            "Roll them onto their side (recovery position): top leg bent forward, head tilted back slightly.",
            "This keeps the airway open and lets vomit drain.",
            "Keep checking that they are breathing until help arrives.",
        ),
        "Diarrhoea and dehydration (ORS)" to listOf(
            "After floods, drink only boiled, filtered or chlorinated water.",
            "Home ORS: 1 litre of clean water + 6 level teaspoons of sugar + half a level teaspoon of salt.",
            "Give small sips often. Keep breastfeeding babies.",
            "Get help for blood in stool, high fever, no urine, or a very sleepy child.",
        ),
    )

    private val TA = listOf(
        "மூச்சு இல்லை - CPR" to listOf(
            "இடம் பாதுகாப்பானதா என்று பாருங்கள். நபரைத் தட்டி உரக்க அழையுங்கள். சிக்னல் இருந்தால் 108 / 112 அழையுங்கள்.",
            "சாதாரணமாக மூச்சு விடவில்லை என்றால், கடினமான தரையில் மல்லாந்து படுக்க வையுங்கள்.",
            "மார்பின் நடுவில் ஒரு கையின் அடிப்பகுதியை வைத்து, அதன் மேல் மற்றொரு கையை வையுங்கள். கைகளை நேராக வைத்திருங்கள்.",
            "வேகமாகவும் அழுத்தமாகவும் அழுத்துங்கள்: சுமார் 5 செ.மீ ஆழம், நிமிடத்திற்கு 100-120 முறை. ஒவ்வொரு அழுத்தத்திற்கும் இடையில் மார்பு முழுமையாக மேலே வர விடுங்கள்.",
            "பயிற்சி இருந்தால், ஒவ்வொரு 30 அழுத்தங்களுக்குப் பின் 2 மூச்சுக் கொடுங்கள். பயிற்சி இல்லையென்றால் நிறுத்தாமல் அழுத்திக்கொண்டே இருங்கள்.",
            "நபர் மூச்சு விடும் வரை, அல்லது உதவி வரும் வரை நிறுத்தாதீர்கள்.",
        ),
        "அதிக இரத்தப்போக்கு" to listOf(
            "சுத்தமான துணியால் அல்லது கையால் காயத்தின் மேல் உறுதியாக அழுத்துங்கள் (முடிந்தால் பிளாஸ்டிக் பையைக் கையுறையாகப் பயன்படுத்துங்கள்).",
            "குறைந்தது 10 நிமிடம் அழுத்திக்கொண்டே இருங்கள். அடிக்கடி துணியைத் தூக்கிப் பார்க்காதீர்கள்.",
            "இரத்தம் ஊறி வந்தால், அதன் மேல் இன்னொரு துணியை வைத்து அழுத்துங்கள்.",
            "முடிந்தால் காயமடைந்த பகுதியை இதயத்தை விட உயரத்தில் வையுங்கள் (எலும்பு முறிவு இருந்தால் வேண்டாம்).",
            "அழுத்தத்தால் நிற்காத உயிருக்கு ஆபத்தான கை/கால் இரத்தப்போக்கிற்கு மட்டும் காயத்திற்கு மேலே இறுக்கமாகக் கட்டுங்கள். நேரத்தைக் குறித்துக்கொள்ளுங்கள்.",
            "நபரைப் படுக்க வைத்து சூடாக வைத்திருங்கள். வெளிறிய, குளிர்ந்த, வியர்த்த தோல் (அதிர்ச்சி) உள்ளதா எனக் கவனியுங்கள்.",
        ),
        "தீக்காயம்" to listOf(
            "எரிவதை நிறுத்துங்கள்: வெப்பத்திலிருந்து விலகுங்கள், தீயை அணையுங்கள் (நில், விழு, உருள்).",
            "சுத்தமான ஓடும் தண்ணீரில் 20 நிமிடம் காயத்தைக் குளிர்விக்கவும்.",
            "தோலில் ஒட்டாமல் இருந்தால் மோதிரம், கடிகாரம், இறுக்கமான உடைகளை அகற்றுங்கள்.",
            "ஐஸ், வெண்ணெய், எண்ணெய், பற்பசை, களிம்பு போடக்கூடாது. கொப்புளங்களை உடைக்காதீர்கள்.",
            "சுத்தமான பிளாஸ்டிக் உறை அல்லது சுத்தமான உலர்ந்த துணியால் தளர்வாக மூடுங்கள்.",
            "பெரிய காயங்கள், முகம்/கை/அந்தரங்க உறுப்புகளில் காயம், குழந்தைகளுக்கான எல்லா காயங்களுக்கும் மருத்துவ உதவி பெறுங்கள்.",
        ),
        "நீரில் மூழ்குதல்" to listOf(
            "பயிற்சி இல்லாமல் தண்ணீரில் குதிக்காதீர்கள். கயிறு, டியூப், பாட்டில் அல்லது மிதக்கும் எதையாவது வீசுங்கள்.",
            "தண்ணீரிலிருந்து வெளியே எடுத்ததும் மூச்சு உள்ளதா என்று பாருங்கள்.",
            "மூச்சு இல்லையென்றால் CPR தொடங்குங்கள். பயிற்சி இருந்தால் முதலில் 5 மூச்சுக் கொடுங்கள்.",
            "மூச்சு இருந்தால், தண்ணீர் வெளியேற பக்கவாட்டில் (மீட்பு நிலை) படுக்க வையுங்கள்.",
            "ஈரமான உடைகளை அகற்றி சூடாக வையுங்கள். மூழ்கி மீண்ட அனைவரும் மருத்துவரைப் பார்க்க வேண்டும்.",
        ),
        "எலும்பு முறிவு" to listOf(
            "எலும்பை நேராக்க முயற்சிக்காதீர்கள். அடிபட்ட பகுதியை அசையாமல் வையுங்கள்.",
            "குச்சி, சுருட்டிய செய்தித்தாள் அல்லது அட்டையை இரு பக்கமும் வைத்து, மெத்தை வைத்துக் கட்டுங்கள்.",
            "காயத்தின் மேலும் கீழும் கட்டுங்கள், காயத்தின் மேல் அல்ல. விரல்கள் சூடாகவும் இளஞ்சிவப்பாகவும் உள்ளதா பாருங்கள்.",
            "திறந்த காயம் இருந்தால் முதலில் சுத்தமான துணியால் மூடுங்கள். எலும்பை உள்ளே தள்ளாதீர்கள்.",
            "கழுத்து அல்லது முதுகில் அடிபட்டவரை ஆபத்து இல்லாவிட்டால் நகர்த்தாதீர்கள்.",
        ),
        "பாம்புக் கடி" to listOf(
            "பாம்பிடமிருந்து விலகுங்கள். நபரை அமைதியாகவும் முடிந்தவரை அசையாமலும் வையுங்கள்.",
            "கடிபட்ட இடத்தின் அருகில் உள்ள மோதிரம், கடிகாரம், இறுக்கமானவற்றை அகற்றுங்கள்.",
            "கடிபட்ட கை/காலை இதய மட்டத்தில் வைத்து, அசையாமல் கட்டை வைத்துக் கட்டுங்கள்.",
            "காயத்தை வெட்டக்கூடாது, விஷத்தை உறிஞ்சக்கூடாது, இறுக்கமாகக் கட்டக்கூடாது, ஐஸ் அல்லது மூலிகை போடக்கூடாது.",
            "உடனே மருத்துவமனைக்குச் செல்லுங்கள் - பாம்பு விஷ முறிவு மருந்து மட்டுமே சிகிச்சை. கடித்த நேரத்தைக் குறித்துக்கொள்ளுங்கள்.",
        ),
        "மின்சாரம் தாக்குதல்" to listOf(
            "நபர் மின் இணைப்பைத் தொட்டுக்கொண்டிருக்கும்போது அவரைத் தொடாதீர்கள்.",
            "முடிந்தால் மின்சாரத்தை அணையுங்கள், அல்லது உலர்ந்த இடத்தில் நின்று உலர்ந்த மரம்/பிளாஸ்டிக்கால் தள்ளுங்கள்.",
            "அறுந்து விழுந்த மின் கம்பிகள் மற்றும் அவற்றின் அருகிலுள்ள வெள்ள நீரிலிருந்து விலகி இருங்கள்.",
            "மூச்சு உள்ளதா பாருங்கள். இல்லையென்றால் CPR தொடங்குங்கள்.",
            "தீக்காயங்களைத் தண்ணீரில் குளிர்வித்து மருத்துவ உதவி பெறுங்கள்.",
        ),
        "வெப்பத் தாக்கு" to listOf(
            "அறிகுறிகள்: மிகவும் சூடான தோல், குழப்பம், தலைவலி, மயக்கம், வியர்வை இல்லாமை.",
            "நபரை நிழலுக்கு அல்லது குளிர்ந்த இடத்திற்கு மாற்றுங்கள்.",
            "விரைவாகக் குளிர்விக்கவும்: தோலை நனைத்து விசிறுங்கள்; கழுத்து, அக்குள், தொடை இடுக்கில் ஈரத் துணி வையுங்கள்.",
            "விழிப்புடன் விழுங்க முடிந்தால் சிறிது சிறிதாகத் தண்ணீர் கொடுங்கள்.",
            "உடனடியாக மருத்துவ உதவி பெறுங்கள்.",
        ),
        "அதிகக் குளிர் (உடல் வெப்பக் குறைவு)" to listOf(
            "நீண்ட நேரம் வெள்ள நீரில் இருந்தபின் வரும். அறிகுறிகள்: நடுக்கம், மெதுவான பேச்சு, குழப்பம்.",
            "நபரை உலர்ந்த, பாதுகாப்பான இடத்திற்கு மாற்றுங்கள். ஈர உடைகளை மெதுவாக அகற்றுங்கள்.",
            "உலர்ந்த போர்வை அல்லது உடைகளால் போர்த்துங்கள், தலையையும் மூடுங்கள். முதலில் உடலின் நடுப்பகுதியைச் சூடாக்குங்கள்.",
            "விழிப்புடன் இருந்தால் சூடான இனிப்பு பானம் கொடுங்கள். மது வேண்டாம்.",
            "கை கால்களை வேகமாகத் தேய்க்காதீர்கள். மருத்துவ உதவி பெறுங்கள்.",
        ),
        "தொண்டையில் அடைப்பு (பெரியவர்)" to listOf(
            "இருமல் வர முடிந்தால், தொடர்ந்து இருமச் சொல்லுங்கள்.",
            "மூச்சு விடவோ பேசவோ இருமவோ முடியாவிட்டால்: தோள்பட்டைகளுக்கு இடையில் 5 முறை உறுதியாகத் தட்டுங்கள்.",
            "பின்னர் 5 முறை வயிற்று அழுத்தம்: பின்னால் நின்று, தொப்புளுக்கு மேல் முஷ்டி வைத்து, உள்ளேயும் மேலேயும் வேகமாக இழுங்கள்.",
            "பொருள் வெளியே வரும் வரை 5 தட்டு, 5 அழுத்தம் மாறி மாறிச் செய்யுங்கள்.",
            "நபர் சுயநினைவு இழந்தால் CPR தொடங்குங்கள்.",
        ),
        "சுயநினைவு இல்லை, மூச்சு உள்ளது" to listOf(
            "நபரைப் பக்கவாட்டில் திருப்புங்கள் (மீட்பு நிலை): மேல் காலை முன்னால் மடக்கி, தலையைச் சற்றுப் பின்னால் சாய்க்கவும்.",
            "இது மூச்சுப்பாதையைத் திறந்து வைக்கும், வாந்தி வெளியேற உதவும்.",
            "உதவி வரும் வரை மூச்சு விடுகிறாரா என்று தொடர்ந்து பாருங்கள்.",
        ),
        "வயிற்றுப்போக்கு, நீரிழப்பு (ORS)" to listOf(
            "வெள்ளத்திற்குப் பின் கொதிக்க வைத்த, வடிகட்டிய அல்லது குளோரின் கலந்த தண்ணீர் மட்டும் குடியுங்கள்.",
            "வீட்டில் ORS: 1 லிட்டர் சுத்தமான தண்ணீர் + 6 சமமான தேக்கரண்டி சர்க்கரை + அரை தேக்கரண்டி உப்பு.",
            "சிறிது சிறிதாக அடிக்கடி கொடுங்கள். குழந்தைகளுக்குத் தாய்ப்பால் தொடருங்கள்.",
            "மலத்தில் இரத்தம், அதிக காய்ச்சல், சிறுநீர் இல்லாமை, மிகவும் தூக்கமான குழந்தை - உடனே உதவி பெறுங்கள்.",
        ),
    )
}
