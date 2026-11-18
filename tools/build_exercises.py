#!/usr/bin/env python3
"""Régénère app/src/main/assets/exercises.json à partir de free-exercise-db (domaine public).

Usage :
    curl -sSfL -o /tmp/fedb.json https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/dist/exercises.json
    python3 tools/build_exercises.py /tmp/fedb.json app/src/main/assets/exercises.json

Traductions : tools/exercises_fr.json ({id: {fr, d}}) donne le nom et les consignes en français ;
le dictionnaire FR ci-dessous (noms choisis à la main) l'emporte pour le nom.

Format compact (clés courtes pour garder l'asset léger) :
    id, n (nom anglais), fr (nom français si connu), m (muscle principal), s (muscles secondaires),
    e (matériel), c (catégorie), l (niveau b/i/e), f (force push/pull/static), k (c = polyarticulaire, i = isolation),
    d (consignes, en français si traduites), i (images si chemin non standard)
"""
import json
import os
import sys

FR = {
    # Gainage / abdos
    "Plank": "Gainage ventral (planche)",
    "Side Bridge": "Gainage latéral",
    "Push Up to Side Plank": "Pompe + gainage latéral",
    "Dead Bug": "Dead bug",
    "Crunches": "Crunch",
    "Reverse Crunch": "Crunch inversé",
    "Cross-Body Crunch": "Crunch croisé",
    "Oblique Crunches - On The Floor": "Crunch obliques au sol",
    "Tuck Crunch": "Crunch genoux groupés",
    "Exercise Ball Crunch": "Crunch sur swiss ball",
    "Exercise Ball Pull-In": "Rentrée de genoux sur swiss ball",
    "Sit-Up": "Relevé de buste",
    "3/4 Sit-Up": "Relevé de buste 3/4",
    "Russian Twist": "Russian twist",
    "Flutter Kicks": "Battements de jambes",
    "Scissor Kick": "Ciseaux",
    "Leg Pull-In": "Rentrée de genoux au sol",
    "Flat Bench Lying Leg Raise": "Relevé de jambes allongé",
    "Mountain Climbers": "Mountain climbers",
    "Air Bike": "Crunch vélo",
    "Alternate Heel Touchers": "Touchers de talons alternés",
    "Elbow to Knee": "Coude-genou",
    "Stomach Vacuum": "Vacuum (transverse)",
    "Ab Roller": "Roue abdominale",
    "Jackknife Sit-Up": "Portefeuille (jackknife)",
    "Bottoms Up": "Relevé de bassin",
    "Spider Crawl": "Marche de l'araignée",
    "Inchworm": "Chenille (inchworm)",
    "Dumbbell Side Bend": "Flexion latérale haltère",
    "Toe Touchers": "Touchers de pointes",
    "Knee Tuck Jump": "Saut genoux poitrine",
    # Bas du dos / chaîne postérieure
    "Superman": "Superman",
    "Hyperextensions With No Hyperextension Bench": "Extension lombaire au sol",
    "Hyperextensions (Back Extensions)": "Extension lombaire au banc",
    "One Half Locust": "Demi-sauterelle",
    "Pelvic Tilt Into Bridge": "Pont fessier (bascule du bassin)",
    "Butt Lift (Bridge)": "Pont fessier",
    "Single Leg Glute Bridge": "Pont fessier unilatéral",
    "Physioball Hip Bridge": "Pont fessier sur swiss ball",
    "Ball Leg Curl": "Leg curl sur swiss ball",
    "Floor Glute-Ham Raise": "Glute-ham raise au sol",
    "Natural Glute Ham Raise": "Nordic hamstring curl",
    "Glute Kickback": "Kickback fessier à quatre pattes",
    "Rear Leg Raises": "Extension de hanche à quatre pattes",
    "Hip Extension with Bands": "Extension de hanche à l'élastique",
    "Stiff-Legged Dumbbell Deadlift": "Soulevé de terre jambes tendues haltères",
    "Kettlebell One-Legged Deadlift": "Soulevé de terre unilatéral kettlebell",
    "One-Arm Kettlebell Swings": "Swing kettlebell une main",
    "Butt-Ups": "Butt-ups (gainage dynamique)",
    # Jambes
    "Bodyweight Squat": "Squat au poids du corps",
    "Goblet Squat": "Goblet squat",
    "Dumbbell Squat": "Squat haltères",
    "Dumbbell Squat To A Bench": "Squat haltères jusqu'au banc",
    "Plie Dumbbell Squat": "Squat sumo haltère",
    "Squats - With Bands": "Squat à l'élastique",
    "Front Squats With Two Kettlebells": "Squat avant deux kettlebells",
    "Sit Squats": "Squat assis-debout",
    "Freehand Jump Squat": "Squat sauté",
    "Bodyweight Walking Lunge": "Fentes marchées",
    "Dumbbell Lunges": "Fentes avant haltères",
    "Dumbbell Rear Lunge": "Fentes arrière haltères",
    "Crossover Reverse Lunge": "Fente arrière croisée",
    "Split Squats": "Fentes sautées alternées",
    "Split Squat with Dumbbells": "Fente statique haltères",
    "Split Jump": "Fentes sautées",
    "Dumbbell Step Ups": "Montées sur banc haltères",
    "Step-up with Knee Raise": "Montée sur banc + genou haut",
    "Single-Leg High Box Squat": "Squat unipodal sur box",
    "Side Leg Raises": "Élévation latérale de jambe",
    "Band Hip Adductions": "Adduction de hanche à l'élastique",
    "Monster Walk": "Marche latérale à l'élastique (monster walk)",
    "Hip Flexion with Band": "Flexion de hanche à l'élastique",
    "Seated Band Hamstring Curl": "Leg curl assis à l'élastique",
    "Calf Raise On A Dumbbell": "Mollets debout sur marche",
    "Standing Dumbbell Calf Raise": "Mollets debout haltères",
    "Calf Raises - With Bands": "Mollets à l'élastique",
    "Dumbbell Seated One-Leg Calf Raise": "Mollets assis unilatéral",
    "Donkey Calf Raises": "Mollets « donkey »",
    "Balance Board": "Planche d'équilibre",
    "Downward Facing Balance": "Équilibre unipodal penché",
    # Pliométrie / gammes de course
    "Front Box Jump": "Saut sur box",
    "Box Jump (Multiple Response)": "Sauts sur box enchaînés",
    "Lateral Bound": "Bonds latéraux",
    "Single-Leg Lateral Hop": "Sauts latéraux unipodaux",
    "Single-Leg Hop Progression": "Sauts unipodaux",
    "Standing Long Jump": "Saut en longueur sans élan",
    "Star Jump": "Saut en étoile",
    "Frog Hops": "Sauts de grenouille",
    "Fast Skipping": "Montées de genoux rapides (skipping)",
    "Double Leg Butt Kick": "Talons-fesses sautés",
    "Single Leg Butt Kick": "Talons-fesses",
    "Carioca Quick Step": "Pas chassés croisés (carioca)",
    "Rope Jumping": "Corde à sauter",
    "Skating": "Sauts du patineur",
    "Hurdle Hops": "Sauts de haies",
    "Lateral Cone Hops": "Sauts latéraux par-dessus un plot",
    "Front Cone Hops (or hurdle hops)": "Sauts avant par-dessus un plot",
    "Scissors Jump": "Sauts en ciseaux",
    "Knee Circles": "Cercles de genoux",
    "Ankle Circles": "Cercles de chevilles",
    # Poitrine / triceps
    "Pushups": "Pompes",
    "Push-Up Wide": "Pompes mains larges",
    "Push-Ups - Close Triceps Position": "Pompes serrées (triceps)",
    "Incline Push-Up": "Pompes inclinées (mains surélevées)",
    "Decline Push-Up": "Pompes déclinées (pieds surélevés)",
    "Push-Ups With Feet Elevated": "Pompes pieds surélevés",
    "Push-Ups With Feet On An Exercise Ball": "Pompes pieds sur swiss ball",
    "Clock Push-Up": "Pompes en horloge",
    "Plyo Push-up": "Pompes sautées",
    "Dumbbell Bench Press": "Développé couché haltères",
    "Dumbbell Flyes": "Écarté couché haltères",
    "Incline Dumbbell Press": "Développé incliné haltères",
    "Alternating Floor Press": "Développé au sol alterné",
    "One-Arm Kettlebell Floor Press": "Développé au sol kettlebell",
    "Bench Press - With Bands": "Développé à l'élastique",
    "Cross Over - With Bands": "Écarté à l'élastique",
    "Bench Dips": "Dips sur banc",
    "Dips - Triceps Version": "Dips (triceps)",
    "Dips - Chest Version": "Dips (pectoraux)",
    "Tricep Dumbbell Kickback": "Kickback triceps haltère",
    "Standing Dumbbell Triceps Extension": "Extension triceps au-dessus de la tête",
    "Lying Dumbbell Tricep Extension": "Extension triceps couché haltères",
    "Band Skull Crusher": "Barre au front à l'élastique",
    "Speed Band Overhead Triceps": "Extension triceps à l'élastique",
    "Kettlebell Overhead Triceps Extension": "Extension triceps kettlebell",
    "Straight-Arm Dumbbell Pullover": "Pull-over haltère",
    # Dos / biceps
    "Pullups": "Tractions pronation",
    "Chin-Up": "Tractions supination",
    "Band Assisted Pull-Up": "Tractions assistées à l'élastique",
    "Scapular Pull-Up": "Tractions scapulaires",
    "Inverted Row": "Rowing inversé (sous une table / barre)",
    "Bodyweight Mid Row": "Rowing au poids du corps",
    "Suspended Row": "Rowing aux sangles",
    "One-Arm Dumbbell Row": "Rowing un bras haltère",
    "Bent Over Two-Dumbbell Row": "Rowing buste penché haltères",
    "One-Arm Kettlebell Row": "Rowing kettlebell un bras",
    "Alternating Kettlebell Row": "Rowing kettlebell alterné",
    "Band Pull Apart": "Écartement d'élastique (band pull apart)",
    "Back Flyes - With Bands": "Oiseau à l'élastique",
    "Reverse Flyes": "Oiseau haltères",
    "Seated Bent-Over Rear Delt Raise": "Oiseau assis",
    "Upright Row - With Bands": "Tirage menton à l'élastique",
    "Dumbbell Shrug": "Shrug haltères",
    "Dumbbell Bicep Curl": "Curl biceps haltères",
    "Dumbbell Alternate Bicep Curl": "Curl biceps alterné",
    "Hammer Curls": "Curl marteau",
    "Concentration Curls": "Curl concentré",
    "Zottman Curl": "Curl Zottman",
    # Épaules
    "Dumbbell Shoulder Press": "Développé épaules haltères",
    "Seated Dumbbell Press": "Développé épaules assis",
    "Arnold Dumbbell Press": "Développé Arnold",
    "Shoulder Press - With Bands": "Développé épaules à l'élastique",
    "Two-Arm Kettlebell Military Press": "Développé militaire kettlebells",
    "Side Lateral Raise": "Élévations latérales",
    "Lateral Raise - With Bands": "Élévations latérales à l'élastique",
    "Front Dumbbell Raise": "Élévations frontales",
    "Dumbbell Scaption": "Élévations en Y (scaption)",
    "External Rotation with Band": "Rotation externe d'épaule à l'élastique",
    "Internal Rotation with Band": "Rotation interne d'épaule à l'élastique",
    "External Rotation": "Rotation externe d'épaule haltère",
    "Cuban Press": "Cuban press",
    "Kettlebell Halo": "Halo kettlebell",
    "Kettlebell Turkish Get-Up (Lunge style)": "Turkish get-up",
    "Kettlebell Windmill": "Moulin kettlebell",
    "Arm Circles": "Moulinets de bras",
    "Shoulder Circles": "Cercles d'épaules",
    "Wrist Circles": "Cercles de poignets",
    "Isometric Neck Exercise - Front And Back": "Gainage de la nuque avant/arrière",
    "Isometric Neck Exercise - Sides": "Gainage de la nuque latéral",
    # Étirements / mobilité
    "Cat Stretch": "Chat-vache",
    "Child's Pose": "Posture de l'enfant",
    "One Knee To Chest": "Genou à la poitrine",
    "Hug Knees To Chest": "Genoux à la poitrine",
    "Hamstring Stretch": "Étirement ischio-jambiers",
    "Seated Floor Hamstring Stretch": "Étirement ischios assis",
    "Leg-Up Hamstring Stretch": "Étirement ischios jambe levée",
    "90/90 Hamstring": "Étirement ischios 90/90",
    "Quad Stretch": "Étirement quadriceps",
    "On Your Side Quad Stretch": "Étirement quadriceps allongé sur le côté",
    "Standing Elevated Quad Stretch": "Étirement quadriceps pied surélevé",
    "All Fours Quad Stretch": "Étirement quadriceps à genoux",
    "Kneeling Hip Flexor": "Étirement psoas à genou",
    "Standing Hip Flexors": "Étirement psoas debout",
    "Intermediate Hip Flexor and Quad Stretch": "Étirement psoas-quadriceps",
    "Runner's Stretch": "Étirement du coureur",
    "Calf Stretch Hands Against Wall": "Étirement mollet contre un mur",
    "Standing Gastrocnemius Calf Stretch": "Étirement mollet (gastrocnémien)",
    "Standing Soleus And Achilles Stretch": "Étirement soléaire et tendon d'Achille",
    "Seated Calf Stretch": "Étirement mollet assis",
    "Posterior Tibialis Stretch": "Étirement tibial postérieur",
    "Peroneals Stretch": "Étirement des péroniers",
    "Adductor": "Étirement adducteurs au rouleau",
    "Side Lying Groin Stretch": "Étirement adducteurs allongé",
    "Intermediate Groin Stretch": "Étirement adducteurs",
    "Groin and Back Stretch": "Étirement adducteurs et dos",
    "IT Band and Glute Stretch": "Étirement bandelette ilio-tibiale et fessier",
    "Knee Across The Body": "Torsion genou croisé",
    "Ankle On The Knee": "Étirement fessier cheville sur genou",
    "Chair Lower Back Stretch": "Étirement lombaires sur chaise",
    "Lower Back Curl": "Enroulement du bas du dos",
    "Spinal Stretch": "Torsion vertébrale assise",
    "Middle Back Stretch": "Étirement milieu du dos",
    "Upper Back Stretch": "Étirement haut du dos",
    "Dynamic Back Stretch": "Étirement dynamique du dos",
    "Standing Lateral Stretch": "Étirement latéral debout",
    "Side-Lying Floor Stretch": "Étirement latéral allongé",
    "World's Greatest Stretch": "Le meilleur étirement du monde (fente + rotation)",
    "Torso Rotation": "Rotation du buste",
    "Standing Pelvic Tilt": "Bascule du bassin debout",
    "Standing Hip Circles": "Cercles de hanches debout",
    "Hip Circles (prone)": "Cercles de hanche à quatre pattes",
    "Chest And Front Of Shoulder Stretch": "Étirement pectoraux et avant d'épaule",
    "Dynamic Chest Stretch": "Étirement dynamique des pectoraux",
    "Shoulder Stretch": "Étirement épaule",
    "Overhead Stretch": "Étirement bras au-dessus de la tête",
    "Triceps Stretch": "Étirement triceps",
    "Standing Biceps Stretch": "Étirement biceps",
    "Kneeling Forearm Stretch": "Étirement avant-bras à genoux",
    "Side Neck Stretch": "Étirement latéral de la nuque",
    "Chin To Chest Stretch": "Étirement nuque menton-poitrine",
    "Looking At Ceiling": "Extension de la nuque",
    "Standing Toe Touches": "Flexion avant debout",
    "Standing Hamstring and Calf Stretch": "Étirement ischios et mollet debout",
    "Seated Hamstring and Calf Stretch": "Étirement ischios et mollet assis",
    "Dancer's Stretch": "Étirement du danseur",
    "Hamstring-SMR": "Auto-massage ischios au rouleau",
    "Calves-SMR": "Auto-massage mollets au rouleau",
    "Quadriceps-SMR": "Auto-massage quadriceps au rouleau",
    "Iliotibial Tract-SMR": "Auto-massage bandelette ilio-tibiale",
    "Piriformis-SMR": "Auto-massage pyramidal au rouleau",
    "Lower Back-SMR": "Auto-massage lombaires au rouleau",
    "Latissimus Dorsi-SMR": "Auto-massage grand dorsal au rouleau",
    "Anterior Tibialis-SMR": "Auto-massage tibial antérieur",
    "Foot-SMR": "Auto-massage voûte plantaire",
    "Peroneals-SMR": "Auto-massage péroniers",
    # Cardio
    "Bicycling": "Vélo",
    "Trail Running/Walking": "Trail / marche",
}

LEVEL = {"beginner": "b", "intermediate": "i", "expert": "e"}
MECH = {"compound": "c", "isolation": "i"}


def convert(x: dict, tr: dict) -> dict:
    out = {"id": x["id"], "n": x["name"]}
    t = tr.get(x["id"], {})
    fr = FR.get(x["name"]) or (t.get("fr") or "").strip()
    if fr:
        out["fr"] = fr
    out["m"] = (x.get("primaryMuscles") or [""])[0]
    sec = [m for m in (x.get("primaryMuscles") or [])[1:] + (x.get("secondaryMuscles") or []) if m != out["m"]]
    if sec:
        out["s"] = sec
    out["e"] = x.get("equipment") or "none"
    out["c"] = x.get("category") or ""
    out["l"] = LEVEL.get(x.get("level"), "b")
    if x.get("force"):
        out["f"] = x["force"]
    if x.get("mechanic") in MECH:
        out["k"] = MECH[x["mechanic"]]
    d = (t.get("d") or "").strip() or " ".join(s.strip() for s in x.get("instructions") or [])
    if d:
        out["d"] = d
    images = x.get("images") or []
    if images != [f"{x['id']}/0.jpg", f"{x['id']}/1.jpg"]:
        out["i"] = images
    return out


def main(src: str, dst: str) -> None:
    data = json.load(open(src, encoding="utf-8"))
    names = {x["name"] for x in data}
    missing = [n for n in FR if n not in names]
    if missing:
        sys.exit("Traductions sans exercice correspondant : " + ", ".join(missing))
    tr_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "exercises_fr.json")
    tr = json.load(open(tr_path, encoding="utf-8")) if os.path.exists(tr_path) else {}
    out = sorted((convert(x, tr) for x in data), key=lambda e: e["id"])
    with open(dst, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    print(f"{len(out)} exercices, {sum('fr' in e for e in out)} noms français, {sum(e['id'] in tr for e in out)} consignes traduites → {dst}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
