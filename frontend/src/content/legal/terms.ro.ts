// GENERATED FILE — do not edit by hand.
// Source: TERMENI SI CONDITII MyFinance_proiect.docx
// Regenerate with: python3 scripts/import_legal_docs.py (see that script's docstring)
//
// Unfilled [DE COMPLETAT] placeholders in this document: 15
import type { LegalDocument } from "./types";

export const termsRo: LegalDocument = {
    "title": "TERMENI ȘI CONDIȚII DE UTILIZARE",
    "subtitle": "MYFINANCE – aplicație de colaborare între firmele de contabilitate și societățile lor cliente",
    "notice": "DOCUMENT DE LUCRU – COMPLETAȚI TOATE CÂMPURILE MARCATE [DE COMPLETAT] ȘI VALIDAȚI FUNCȚIONALITĂȚILE EFECTIV LIVRATE ÎNAINTE DE PUBLICARE. Acest proiect a fost redactat pornind de la funcționalitatea reală a aplicației MyFinance și este corelat cu Politica de confidențialitate MyFinance. Nu constituie consultanță juridică și nu confirmă, prin el însuși, conformitatea juridică sau tehnică a aplicației.",
    "intro": [
      {
        "kind": "p",
        "text": "Versiune: 0.1 (proiect) · Data ultimei actualizări: [DE COMPLETAT]"
      }
    ],
    "sections": [
      {
        "heading": "1. Cine suntem",
        "blocks": [
          {
            "kind": "p",
            "text": "Aplicația MyFinance („Aplicația” sau „Platforma”) este operată de INNOVATE CODE SRL, cu sediul în Cluj-Napoca, Strada Ștefan Augustin Doinaș nr. 2B-2C, având CUI 49443957, înregistrată la Registrul Comerțului sub nr. [DE COMPLETAT], denumită în continuare „Furnizorul” sau „noi”."
          },
          {
            "kind": "p",
            "text": "Date de contact: e-mail dmarius23@gmail.com, telefon [DE COMPLETAT]. Adresa web la care este disponibilă Aplicația: [DE COMPLETAT]."
          },
          {
            "kind": "p",
            "text": "MyFinance este o aplicație de tip software-as-a-service (SaaS), destinată exclusiv utilizării profesionale. Aplicația nu este un serviciu de contabilitate, nu este un program de contabilitate autorizat și nu furnizează consultanță contabilă, fiscală, juridică sau financiară."
          }
        ]
      },
      {
        "heading": "2. Ce reglementează acest document și cui i se adresează",
        "blocks": [
          {
            "kind": "p",
            "text": "Prezentul document („Termenii”) stabilește condițiile în care Aplicația poate fi accesată și utilizată. Termenii se aplică tuturor persoanelor care accesează Aplicația, indiferent de rol."
          },
          {
            "kind": "p",
            "text": "Termenii se completează cu: (i) contractul comercial / oferta acceptată, încheiat separat între Furnizor și firma de contabilitate; (ii) Politica de confidențialitate MyFinance; și (iii) acordul de prelucrare a datelor (DPA) încheiat conform art. 28 GDPR. În caz de neconcordanță între prezentul document și contractul comercial semnat, prevalează contractul comercial."
          },
          {
            "kind": "p",
            "text": "Accesarea și utilizarea Aplicației presupun că utilizatorul a citit, a înțeles și a acceptat integral Termenii. Dacă nu sunteți de acord, integral sau parțial, cu Termenii, vă rugăm să nu utilizați Aplicația."
          }
        ]
      },
      {
        "heading": "3. Părțile și categoriile de utilizatori",
        "blocks": [
          {
            "kind": "p",
            "text": "Prezenții Termeni se încheie între Furnizor și firma de contabilitate care subscrie serviciul, denumită în continuare „Clientul”."
          },
          {
            "kind": "p",
            "text": "Reprezentanții societăților cliente ale Clientului („Utilizatorii Finali”) accesează Aplicația pe baza invitației emise de Client. Aceștia acceptă regulile de utilizare prevăzute în prezentul document, însă nu au un raport comercial direct cu Furnizorul; relația lor contractuală privind serviciile de contabilitate există exclusiv față de Client."
          },
          {
            "kind": "p",
            "text": "Rolurile disponibile în Aplicație sunt:"
          },
          {
            "kind": "bullets",
            "items": [
              "Administrator de firmă – utilizator al Clientului, cu acces complet în cadrul firmei: administrează societățile cliente, personalul, setările și invitațiile.",
              "Angajat – utilizator al Clientului, cu acces la documentele și operațiunile societăților cliente pe care le gestionează.",
              "Reprezentant – utilizator al unei societăți cliente, cu acces limitat exclusiv la propria societate, pentru încărcarea documentelor și vizualizarea situației acestora.",
              "Administrator de platformă – rol tehnic rezervat Furnizorului, pentru operarea și mentenanța Platformei."
            ]
          },
          {
            "kind": "p",
            "text": "Clientul răspunde pentru desemnarea utilizatorilor săi și ai societăților cliente, pentru nivelul de acces acordat fiecăruia și pentru retragerea accesului atunci când acesta nu mai este justificat."
          }
        ]
      },
      {
        "heading": "4. Definiții",
        "blocks": [
          {
            "kind": "bullets",
            "items": [
              "Tenant – spațiul de lucru izolat al unui Client în cadrul Platformei. Datele unui tenant nu sunt accesibile altui tenant.",
              "Societate clientă – societatea comercială pentru care Clientul prestează servicii de contabilitate și a cărei evidență este gestionată în Aplicație.",
              "Document-sursă – orice document încărcat sau preluat în Aplicație: extras de cont, factură, bon, balanță de verificare, declarație fiscală, document de salarizare sau alt document justificativ.",
              "Extragere automată – procesul tehnic prin care Aplicația identifică date (sume, date calendaristice, denumiri, coduri fiscale, IBAN) din Documentele-sursă.",
              "Reconciliere – operațiunea de corelare a tranzacțiilor din extrasul de cont cu documentele justificative aferente, în urma căreia Aplicația semnalează documentele lipsă.",
              "Sursă conectată – cont de e-mail sau folder de stocare în cloud pe care Clientul îl conectează la Aplicație pentru preluarea automată a documentelor.",
              "Perioadă de raportare – luna calendaristică pentru care se urmărește completitudinea documentelor și se generează rapoarte."
            ]
          }
        ]
      },
      {
        "heading": "5. Obiectul serviciului: ce face și ce nu face MyFinance",
        "blocks": [
          {
            "kind": "p",
            "text": "Aplicația pune la dispoziția Clientului și a Utilizatorilor Finali, în limita modulelor activate, următoarele funcționalități:"
          },
          {
            "kind": "bullets",
            "items": [
              "colectarea Documentelor-sursă prin încărcare manuală de către reprezentanți sau de către personalul Clientului;",
              "preluarea automată a documentelor din Surse conectate, în limitele permisiunilor acordate de Client;",
              "extragerea automată a datelor din Documentele-sursă și organizarea acestora pe societate și perioadă de raportare;",
              "reconcilierea tranzacțiilor bancare cu documentele justificative și semnalarea documentelor lipsă, a duplicatelor și a neconcordanțelor;",
              "calcularea și afișarea de indicatori, sinteze, rapoarte și tablouri de bord pe baza datelor furnizate;",
              "generarea și transmiterea, la inițiativa utilizatorilor autorizați, a comunicărilor către societățile cliente (sume de plată către stat, raport lunar, memento-uri privind documentele lipsă, salarizare);",
              "gestionarea sarcinilor, a notificărilor și a istoricului operațiunilor."
            ]
          },
          {
            "kind": "p",
            "text": "Pentru evitarea oricărei neînțelegeri, Aplicația NU:"
          },
          {
            "kind": "bullets",
            "items": [
              "nu ține evidența contabilă și nu înlocuiește programul de contabilitate al Clientului;",
              "nu întocmește, nu semnează și nu depune declarații fiscale la autorități;",
              "nu efectuează plăți, nu procesează tranzacții financiare și nu include un procesator de plăți;",
              "nu emite facturi în numele Clientului sau al societăților cliente;",
              "nu furnizează consultanță contabilă, fiscală, juridică sau de investiții;",
              "nu înlocuiește raționamentul profesional al contabilului și nu preia răspunderea profesională a acestuia."
            ]
          },
          {
            "kind": "p",
            "text": "Răspunderea pentru corectitudinea evidenței contabile, pentru întocmirea și depunerea declarațiilor și pentru respectarea termenelor legale rămâne integral a Clientului și a societăților cliente, potrivit legislației aplicabile și contractelor dintre acestea."
          }
        ]
      },
      {
        "heading": "6. Crearea conturilor și accesul",
        "blocks": [
          {
            "kind": "p",
            "text": "Aplicația nu permite înregistrarea liberă. Conturile se creează exclusiv prin invitație: Furnizorul configurează contul de administrator al Clientului, iar acesta invită personalul propriu și reprezentanții societăților cliente. Invitația se transmite pe adresa de e-mail indicată, iar utilizatorul își stabilește propria parolă prin linkul primit."
          },
          {
            "kind": "p",
            "text": "Pentru a utiliza Aplicația, utilizatorul trebuie să aibă vârsta de minim 18 ani și capacitate deplină de exercițiu, să fie desemnat de Client sau de societatea clientă și să accepte prezenții Termeni."
          },
          {
            "kind": "p",
            "text": "Fiecare utilizator primește credențiale individuale. Sunt interzise partajarea credențialelor, utilizarea unui cont de către mai multe persoane și accesarea Aplicației cu credențialele altei persoane. Utilizatorul răspunde pentru toate operațiunile efectuate prin contul său."
          },
          {
            "kind": "p",
            "text": "Orice pierdere, divulgare sau suspiciune de compromitere a credențialelor trebuie comunicată de îndată Clientului și, după caz, Furnizorului, la adresa de e-mail indicată la art. 1."
          }
        ]
      },
      {
        "heading": "7. Obligațiile Clientului și ale utilizatorilor",
        "blocks": [
          {
            "kind": "p",
            "text": "Clientul se obligă să:"
          },
          {
            "kind": "bullets",
            "items": [
              "se asigure că are dreptul să încarce, să conecteze sau să transmită în Aplicație documentele și datele societăților cliente, inclusiv datele cu caracter personal cuprinse în acestea;",
              "încheie cu fiecare societate clientă documentele necesare și să îndeplinească obligațiile de informare față de persoanele vizate, atunci când acestea îi revin;",
              "administreze corect utilizatorii proprii și ai societăților cliente și să revoce fără întârziere accesul persoanelor care nu mai sunt autorizate;",
              "verifice datele extrase automat înainte de a le utiliza în evidența contabilă, în declarații sau în comunicările transmise societăților cliente;",
              "nu încarce în Aplicație date care nu sunt necesare funcționalității utilizate, în special categorii speciale de date în sensul art. 9 GDPR;",
              "utilizeze Aplicația în conformitate cu legislația aplicabilă și cu prezenții Termeni."
            ]
          },
          {
            "kind": "p",
            "text": "Utilizatorii Finali se obligă să încarce documente care aparțin societății pe care o reprezintă, complete și lizibile, în formatele acceptate, și să nu încarce fișiere care conțin cod malițios sau conținut ilicit."
          }
        ]
      },
      {
        "heading": "8. Preț și plată",
        "blocks": [
          {
            "kind": "p",
            "text": "Prețul serviciului, durata abonamentului, modalitatea și termenele de plată sunt stabilite în contractul comercial / oferta acceptată, încheiat separat între Furnizor și Client."
          },
          {
            "kind": "p",
            "text": "Aplicația nu procesează plăți și nu include un procesator de plăți. Facturarea se realizează în afara Aplicației, potrivit contractului comercial."
          },
          {
            "kind": "p",
            "text": "Utilizatorii Finali nu datorează Furnizorului nicio sumă pentru accesul la Aplicație."
          }
        ]
      },
      {
        "heading": "9. Încărcarea documentelor și sursele conectate",
        "blocks": [
          {
            "kind": "p",
            "text": "Documentele pot ajunge în Aplicație prin: încărcare manuală de către reprezentantul societății cliente, încărcare de către personalul Clientului, preluare automată din contul de e-mail conectat sau preluare automată din folderul de stocare în cloud conectat."
          },
          {
            "kind": "p",
            "text": "Formatele acceptate includ, în funcție de tipul documentului: fișiere PDF (text sau scanate), imagini ale bonurilor, fișiere XML de tip e-Factura, fișiere de extras de cont în formate standard (MT940, CAMT.053) sau în formate proprietare ale instituțiilor bancare suportate, fișiere de calcul pentru balanța de verificare și declarații fiscale în formatele publicate de autoritatea fiscală. Lista exactă a formatelor și a instituțiilor bancare suportate la un moment dat este cea comunicată Clientului: [DE COMPLETAT]."
          },
          {
            "kind": "p",
            "text": "Conectarea unei Surse conectate se face de către Client, prin autorizare explicită, și poate fi revocată oricând de acesta. Furnizorul accesează conținutul Sursei conectate exclusiv în limita permisiunilor acordate și numai în scopul furnizării serviciului."
          },
          {
            "kind": "p",
            "text": "Canalul WhatsApp este utilizat de Aplicație exclusiv pentru transmiterea de comunicări. Aplicația nu preia documente prin WhatsApp."
          },
          {
            "kind": "p",
            "text": "Aplicația poate identifica și semnala documentele încărcate de mai multe ori, pe baza conținutului acestora. Furnizorul nu garantează recuperarea documentelor pierdute din alte cauze decât cele imputabile Platformei; Clientul are obligația de a păstra exemplarele originale potrivit obligațiilor sale legale de arhivare."
          }
        ]
      },
      {
        "heading": "10. Extragerea automată a datelor. Caracterul neautoritativ al sumelor",
        "blocks": [
          {
            "kind": "p",
            "text": "Aplicația extrage date din Documentele-sursă folosind, în primul rând, prelucrarea deterministă a conținutului text al documentului. Atunci când conținutul text nu este lizibil – de exemplu în cazul bonurilor fotografiate – Aplicația poate recurge la recunoașterea optică a caracterelor prin servicii terțe de procesare (inclusiv servicii de inteligență artificială), în condițiile prezentate în Politica de confidențialitate."
          },
          {
            "kind": "callout",
            "text": "Sumele și celelalte date obținute prin extragere automată au caracter informativ și NU sunt autoritative. Acestea devin utilizabile numai după verificarea și confirmarea lor de către un utilizator autorizat al Clientului, prin operațiunile de reconciliere și validare disponibile în Aplicație. Aplicația nu transmite societăților cliente sume care nu au parcurs această verificare."
          },
          {
            "kind": "p",
            "text": "Acuratețea extragerii depinde de calitatea, lizibilitatea, formatul și completitudinea documentului-sursă. Furnizorul nu garantează extragerea corectă a tuturor datelor din orice document și nu răspunde pentru consecințele utilizării unor date extrase care nu au fost verificate de Client."
          },
          {
            "kind": "p",
            "text": "Clientul are obligația de a verifica datele extrase prin raportare la documentul-sursă înainte de a le înregistra în evidența contabilă, de a le include în declarații sau de a le comunica societăților cliente."
          }
        ]
      },
      {
        "heading": "11. Reconcilierea și semnalarea documentelor lipsă",
        "blocks": [
          {
            "kind": "p",
            "text": "Pe baza tranzacțiilor din extrasul de cont și a documentelor justificative disponibile, Aplicația corelează tranzacțiile cu documentele aferente și poate semnala: stadiul de completitudine al unei societăți pentru perioada de raportare, tranzacțiile pentru care nu există document justificativ, documentele neachitate, duplicatele, documentele emise către o altă societate decât cea în cauză și documentele din afara perioadei."
          },
          {
            "kind": "p",
            "text": "Regulile de clasificare a tranzacțiilor și propunerile de corelare au caracter de sugestie. Decizia privind corelarea, clasificarea și tratamentul contabil aparține exclusiv utilizatorului autorizat al Clientului, care poate accepta, modifica sau respinge orice propunere a Aplicației."
          },
          {
            "kind": "p",
            "text": "Starea de „complet” afișată de Aplicație reflectă exclusiv corelarea documentelor disponibile în Aplicație și nu constituie o confirmare a conformității evidenței contabile sau a îndeplinirii obligațiilor fiscale."
          }
        ]
      },
      {
        "heading": "12. Comunicările transmise prin Aplicație",
        "blocks": [
          {
            "kind": "p",
            "text": "Aplicația permite transmiterea către societățile cliente a unor comunicări operaționale, pe canalele configurate de Client: e-mail, WhatsApp și notificări în aplicație / notificări push. Tipurile de comunicări sunt: sumele de plată către stat, raportul lunar, memento privind salarizarea și memento-uri privind documentele lipsă sau nereceptionate."
          },
          {
            "kind": "p",
            "text": "Conținutul acestor comunicări se generează pe baza datelor existente în Aplicație și a șabloanelor configurate de Client. Transmiterea se face la inițiativa unui utilizator autorizat al Clientului sau potrivit configurării realizate de acesta. Clientul este expeditorul acestor comunicări și răspunde pentru corectitudinea conținutului, pentru oportunitatea transmiterii și pentru existența unui temei legal pentru contactarea destinatarilor."
          },
          {
            "kind": "p",
            "text": "Disponibilitatea canalului WhatsApp depinde de furnizorul de mesagerie configurat, de aprobarea șabloanelor de către acesta și de limitele de volum aplicabile. Furnizorul nu garantează livrarea mesajelor transmise prin canale aparținând unor terți și nu răspunde pentru întârzierile, filtrarea sau blocarea acestora."
          },
          {
            "kind": "p",
            "text": "Furnizorul poate transmite utilizatorilor comunicări administrative și de securitate necesare funcționării serviciului. Comunicările de marketing se transmit numai în condițiile legii și, atunci când este necesar, pe baza consimțământului, cu posibilitatea dezabonării."
          }
        ]
      },
      {
        "heading": "13. Documentele de salarizare și datele sensibile",
        "blocks": [
          {
            "kind": "p",
            "text": "Documentele de salarizare se păstrează în Aplicație în forma în care au fost încărcate, ca fișiere atașate. Aplicația nu extrage și nu calculează sume salariale din aceste documente; ele pot fi vizualizate și transmise societății cliente de către utilizatorii autorizați."
          },
          {
            "kind": "p",
            "text": "Documentele de salarizare pot conține date cu caracter personal privind angajații. Clientul se obligă să încarce și să transmită astfel de documente numai în limitele necesității și ale temeiului legal aplicabil și să acorde acces la acestea exclusiv persoanelor autorizate."
          }
        ]
      },
      {
        "heading": "14. Disponibilitatea serviciului, mentenanța și suportul",
        "blocks": [
          {
            "kind": "p",
            "text": "Furnizorul depune eforturi rezonabile pentru funcționarea continuă a Aplicației, însă serviciul este furnizat „așa cum este” și „așa cum este disponibil”, fără garanția funcționării neîntrerupte sau lipsite de erori."
          },
          {
            "kind": "p",
            "text": "Nivelul de disponibilitate garantat, intervalele de mentenanță planificată, timpii de răspuns la incidente și condițiile de suport, dacă au fost convenite, sunt cele prevăzute în contractul comercial: [DE COMPLETAT]."
          },
          {
            "kind": "p",
            "text": "Furnizorul poate întrerupe temporar accesul pentru operațiuni de mentenanță, actualizare sau securitate, cu informarea prealabilă a Clientului atunci când este posibil. Furnizorul poate modifica, adăuga sau retrage funcționalități ale Aplicației; modificările care afectează semnificativ utilizarea vor fi comunicate Clientului într-un termen rezonabil."
          },
          {
            "kind": "p",
            "text": "Furnizorul nu răspunde pentru indisponibilitatea cauzată de infrastructura, rețelele, dispozitivele sau serviciile terțe aflate în afara controlului său, inclusiv de indisponibilitatea Surselor conectate sau a serviciilor de mesagerie."
          }
        ]
      },
      {
        "heading": "15. Securitatea și izolarea datelor între clienți",
        "blocks": [
          {
            "kind": "p",
            "text": "Aplicația este concepută astfel încât datele fiecărui tenant să fie izolate, iar accesul să fie acordat individual și în funcție de rol. Reprezentanții au acces exclusiv la societatea pe care o reprezintă. Izolarea este aplicată la nivelul bazei de date, nu doar în interfață."
          },
          {
            "kind": "p",
            "text": "Măsurile tehnice și organizatorice efectiv implementate sunt descrise în Politica de confidențialitate și în acordul de prelucrare a datelor. Nu este publicată ca implementată nicio măsură care nu a fost confirmată tehnic și operațional."
          },
          {
            "kind": "p",
            "text": "Clientul rămâne responsabil pentru gestionarea utilizatorilor proprii, pentru securitatea credențialelor, pentru dispozitivele de pe care se accesează Aplicația și pentru acordarea accesului numai persoanelor autorizate."
          }
        ]
      },
      {
        "heading": "16. Protecția datelor cu caracter personal",
        "blocks": [
          {
            "kind": "p",
            "text": "Prelucrarea datelor cu caracter personal este descrisă în Politica de confidențialitate MyFinance, care face parte integrantă din prezenții Termeni."
          },
          {
            "kind": "p",
            "text": "În ceea ce privește datele contabile și datele personale cuprinse în acestea, societatea clientă acționează, de regulă, ca operator, Clientul poate acționa ca operator sau persoană împuternicită potrivit raportului său contractual, iar Furnizorul acționează ca persoană împuternicită, prelucrând datele exclusiv pentru furnizarea serviciului și conform instrucțiunilor documentate."
          },
          {
            "kind": "p",
            "text": "Pentru datele necesare administrării propriului serviciu – crearea și administrarea conturilor, facturarea, suportul, securitatea și jurnalele tehnice – Furnizorul acționează ca operator independent."
          },
          {
            "kind": "p",
            "text": "Utilizarea Aplicației presupune încheierea unui acord de prelucrare a datelor conform art. 28 GDPR. Prezenții Termeni nu înlocuiesc acest acord."
          }
        ]
      },
      {
        "heading": "17. Proprietatea intelectuală și drepturile asupra datelor",
        "blocks": [
          {
            "kind": "p",
            "text": "Aplicația, codul-sursă, structura, interfețele, documentația, denumirea și elementele grafice sunt proprietatea Furnizorului sau a licențiatorilor săi și sunt protejate de legislația privind drepturile de proprietate intelectuală."
          },
          {
            "kind": "p",
            "text": "Clientul primește, pe durata contractului, un drept neexclusiv, netransferabil și nesublicențiabil de a utiliza Aplicația în scopul propriei activități și a societăților cliente pe care le deservește. Sunt interzise copierea, decompilarea, ingineria inversă, extragerea sistematică a conținutului, revânzarea sau punerea la dispoziția terților a Aplicației, în lipsa acordului scris al Furnizorului."
          },
          {
            "kind": "p",
            "text": "Documentele și datele încărcate în Aplicație rămân proprietatea Clientului, respectiv a societăților cliente. Furnizorul nu dobândește drepturi asupra acestora și nu le utilizează în alte scopuri decât furnizarea și securizarea serviciului, respectarea legii și instrucțiunile Clientului."
          },
          {
            "kind": "p",
            "text": "Dacă Furnizorul utilizează date agregate și anonimizate pentru îmbunătățirea serviciului, acest lucru se va face numai în condiții care exclud identificarea Clientului, a societăților cliente și a persoanelor vizate: [DE COMPLETAT] (de confirmat dacă această prelucrare are loc efectiv)."
          }
        ]
      },
      {
        "heading": "18. Utilizarea interzisă a Aplicației",
        "blocks": [
          {
            "kind": "p",
            "text": "Constituie utilizare interzisă, printre altele:"
          },
          {
            "kind": "bullets",
            "items": [
              "încercarea de a accesa datele altui tenant, ale altei societăți cliente sau contul altui utilizator;",
              "încercarea de a ocoli mecanismele de autentificare, de autorizare sau de izolare a datelor;",
              "testarea securității, scanarea sau atacarea infrastructurii fără acordul scris prealabil al Furnizorului;",
              "încărcarea de fișiere care conțin cod malițios sau conținut ilicit;",
              "extragerea automatizată masivă a conținutului, supraîncărcarea deliberată a serviciului sau utilizarea unor volume vădit disproporționate față de utilizarea normală;",
              "utilizarea Aplicației în scopuri contrare legii sau în alte scopuri decât cele pentru care a fost pusă la dispoziție."
            ]
          },
          {
            "kind": "p",
            "text": "Orice tentativă de acces neautorizat la datele altor utilizatori sau de afectare a funcționării Platformei va fi tratată ca incident de securitate, va fi documentată și poate fi sesizată organelor competente."
          }
        ]
      },
      {
        "heading": "19. Suspendarea și încetarea accesului",
        "blocks": [
          {
            "kind": "p",
            "text": "Furnizorul poate suspenda, total sau parțial, accesul la Aplicație în caz de: utilizare interzisă potrivit art. 18, incident de securitate care impune o măsură imediată, obligație legală, sau neplată în condițiile prevăzute în contractul comercial. Suspendarea va fi notificată Clientului și va fi limitată la ceea ce este necesar."
          },
          {
            "kind": "p",
            "text": "Clientul poate oricând revoca accesul oricărui utilizator propriu sau al unei societăți cliente, direct din Aplicație."
          },
          {
            "kind": "p",
            "text": "Încetarea contractului are loc în condițiile prevăzute în contractul comercial. La încetare, accesul la Aplicație este dezactivat, iar datele sunt tratate potrivit art. 20."
          }
        ]
      },
      {
        "heading": "20. Exportul și ștergerea datelor",
        "blocks": [
          {
            "kind": "p",
            "text": "La cererea unei persoane vizate, Aplicația permite exportul datelor personale ale unui utilizator (date de profil și istoricul acțiunilor atribuite acestuia) și scoaterea din uz a contului prin anonimizarea datelor de identificare, cu păstrarea înregistrărilor necesare integrității evidențelor și respectării obligațiilor legale de păstrare."
          },
          {
            "kind": "p",
            "text": "Procedura și termenele pentru exportul integral al datelor unui tenant sau ale unei societăți cliente la încetarea contractului, precum și pentru ștergerea sau returnarea acestora, sunt cele stabilite în acordul de prelucrare a datelor și în contractul comercial: [DE COMPLETAT]."
          },
          {
            "kind": "p",
            "text": "Clientul are obligația de a solicita exportul datelor înainte de expirarea termenului convenit după încetarea contractului. După acest termen, Furnizorul poate șterge datele, cu excepția copiilor care trebuie păstrate în temeiul unei obligații legale."
          }
        ]
      },
      {
        "heading": "21. Răspunderea",
        "blocks": [
          {
            "kind": "p",
            "text": "Furnizorul răspunde pentru furnizarea Aplicației în condițiile prevăzute în prezenții Termeni și în contractul comercial. Furnizorul nu răspunde pentru:"
          },
          {
            "kind": "bullets",
            "items": [
              "corectitudinea evidenței contabile, a declarațiilor fiscale sau a oricărei prelucrări realizate de Client ori de societățile cliente pe baza datelor din Aplicație;",
              "consecințele utilizării unor date extrase automat care nu au fost verificate potrivit art. 10;",
              "conținutul, oportunitatea sau temeiul legal al comunicărilor transmise prin Aplicație de către Client;",
              "calitatea, lizibilitatea sau completitudinea documentelor încărcate ori preluate din Sursele conectate;",
              "nerespectarea termenelor legale de depunere sau de plată de către Client sau de către societățile cliente;",
              "funcționarea serviciilor terțe (instituții bancare, autorități, furnizori de e-mail, stocare în cloud sau mesagerie) și indisponibilitatea acestora;",
              "pierderile rezultate din divulgarea credențialelor sau din acordarea accesului unor persoane neautorizate de către Client."
            ]
          },
          {
            "kind": "p",
            "text": "În măsura permisă de lege, răspunderea Furnizorului este limitată la prejudiciul direct și efectiv, dovedit, și nu include beneficiul nerealizat, pierderea de date imputabilă Clientului, pierderea de clientelă sau prejudiciile indirecte. Limita maximă a răspunderii este cea prevăzută în contractul comercial: [DE COMPLETAT]."
          },
          {
            "kind": "p",
            "text": "Limitările de mai sus nu se aplică în caz de dol, culpă gravă sau în alte situații în care legea nu permite limitarea răspunderii."
          }
        ]
      },
      {
        "heading": "22. Forța majoră",
        "blocks": [
          {
            "kind": "p",
            "text": "Niciuna dintre părți nu răspunde pentru neexecutarea obligațiilor sale dacă aceasta este determinată de un caz de forță majoră, în condițiile legii. Partea afectată va notifica cealaltă parte într-un termen rezonabil și va depune eforturi pentru limitarea efectelor."
          }
        ]
      },
      {
        "heading": "23. Subcontractarea și cesiunea",
        "blocks": [
          {
            "kind": "p",
            "text": "Pentru furnizarea serviciului, Furnizorul utilizează furnizori terți de găzduire, stocare, bază de date, e-mail, mesagerie și procesare a documentelor. Aceștia acționează ca subîmputerniciți, în condițiile acordului de prelucrare a datelor. Lista actualizată a subîmputerniciților, serviciul prestat, localizarea prelucrării și mecanismul de transfer aplicabil sunt prezentate în anexa la acordul de prelucrare: [DE COMPLETAT]."
          },
          {
            "kind": "p",
            "text": "Furnizorul rămâne răspunzător față de Client pentru modul în care subîmputerniciții își îndeplinesc obligațiile aferente serviciului."
          },
          {
            "kind": "p",
            "text": "Furnizorul poate cesiona contractul în cadrul unei reorganizări sau al unui transfer de activitate, cu informarea prealabilă a Clientului. Clientul poate cesiona contractul numai cu acordul scris al Furnizorului."
          }
        ]
      },
      {
        "heading": "24. Modificarea Termenilor",
        "blocks": [
          {
            "kind": "p",
            "text": "Furnizorul poate actualiza prezenții Termeni pentru a reflecta modificări ale Aplicației, ale modului de furnizare a serviciului sau ale cerințelor legale. Versiunea actualizată se publică în Aplicație, cu indicarea datei ultimei actualizări."
          },
          {
            "kind": "p",
            "text": "Modificările care afectează semnificativ drepturile sau obligațiile Clientului vor fi comunicate acestuia cu un preaviz rezonabil, de cel puțin [DE COMPLETAT] zile înainte de intrarea în vigoare. Continuarea utilizării Aplicației după intrarea în vigoare a modificărilor echivalează cu acceptarea acestora. Dacă Clientul nu acceptă modificările, poate înceta contractul în condițiile prevăzute în acesta."
          }
        ]
      },
      {
        "heading": "25. Legea aplicabilă. Soluționarea litigiilor",
        "blocks": [
          {
            "kind": "p",
            "text": "Prezenții Termeni sunt supuși legii române."
          },
          {
            "kind": "p",
            "text": "Părțile vor încerca soluționarea pe cale amiabilă a oricărui litigiu, partea nemulțumită transmițând în prealabil o notificare scrisă, printr-un mijloc apt să facă dovada recepționării. În lipsa unei soluții amiabile, litigiile vor fi soluționate de instanțele judecătorești competente de la sediul Furnizorului: [DE COMPLETAT] (de confirmat clauza de competență)."
          },
          {
            "kind": "p",
            "text": "Aplicația este destinată utilizării profesionale, în relații între profesioniști. Dacă, într-o situație concretă, un utilizator are calitatea de consumator în sensul legislației aplicabile, drepturile sale legale în această calitate nu sunt afectate de prezenții Termeni, iar clauzele aplicabile trebuie completate corespunzător: [DE COMPLETAT] (de verificat împreună cu un jurist)."
          }
        ]
      },
      {
        "heading": "26. Contact",
        "blocks": [
          {
            "kind": "p",
            "text": "Pentru orice întrebare privind prezenții Termeni sau utilizarea Aplicației, ne puteți contacta la:"
          },
          {
            "kind": "p",
            "text": "Furnizor: INNOVATE CODE SRL"
          },
          {
            "kind": "p",
            "text": "Adresă: Cluj-Napoca, Strada Ștefan Augustin Doinaș nr. 2B-2C"
          },
          {
            "kind": "p",
            "text": "CUI: 49443957 · Nr. ONRC: [DE COMPLETAT]"
          },
          {
            "kind": "p",
            "text": "E-mail: dmarius23@gmail.com"
          }
        ]
      },
      {
        "heading": "ANEXĂ – Informații care trebuie completate înainte de publicare",
        "blocks": [
          {
            "kind": "bullets",
            "items": [
              "☐ Numărul de înregistrare la Registrul Comerțului și telefonul de contact al Furnizorului.",
              "☐ Adresa web (domeniul) la care este publicată Aplicația și adresa la care se publică prezenții Termeni.",
              "☐ Data intrării în vigoare și versiunea documentului; mecanismul de arhivare a versiunilor anterioare.",
              "☐ Lista formatelor de fișiere și a instituțiilor bancare efectiv suportate, actualizată.",
              "☐ Nivelul de disponibilitate (SLA), intervalele de mentenanță și condițiile de suport, dacă sunt convenite.",
              "☐ Limita maximă a răspunderii și corelarea acesteia cu contractul comercial și cu polița de asigurare, dacă există.",
              "☐ Preavizul pentru modificarea Termenilor (număr de zile).",
              "☐ Clauza de competență teritorială, confirmată cu un jurist.",
              "☐ Procedura și termenele pentru exportul și ștergerea datelor la încetarea contractului.",
              "☐ Lista subîmputerniciților și anexa la acordul de prelucrare a datelor (DPA) conform art. 28 GDPR.",
              "☐ Confirmarea dacă se realizează prelucrări pe date agregate/anonimizate pentru îmbunătățirea serviciului.",
              "☐ Verificarea aplicabilității legislației privind protecția consumatorilor pentru categoriile de utilizatori vizate.",
              "☐ Mecanismul prin care utilizatorii acceptă Termenii în aplicație (bifă la prima autentificare, acceptare la stabilirea parolei) și păstrarea dovezii acceptării.",
              "☐ Corelarea prezentului document cu Politica de confidențialitate, cu politica de cookie-uri (dacă este cazul) și cu contractul comercial."
            ]
          }
        ]
      },
      {
        "heading": "Notă juridică importantă",
        "blocks": [
          {
            "kind": "p",
            "text": "Acest document este un proiect de redactare întocmit pe baza funcționalității efectiv identificate în aplicația MyFinance și corelat cu Politica de confidențialitate MyFinance. Nu reprezintă consultanță juridică și nu poate fi publicat fără completarea informațiilor lipsă și fără revizuirea de către un avocat."
          },
          {
            "kind": "p",
            "text": "Este recomandată verificarea corelării dintre prezenții Termeni, Politica de confidențialitate, acordul de prelucrare a datelor (DPA) și contractul comercial încheiat cu fiecare firmă de contabilitate, astfel încât obligațiile asumate să fie consecvente și susținute de implementarea tehnică reală."
          },
          {
            "kind": "p",
            "text": "Repere de verificare: Codul civil; Legea nr. 365/2002 privind comerțul electronic; O.U.G. nr. 34/2014 (în măsura în care este aplicabilă); Regulamentul (UE) 2016/679 (GDPR), în special art. 5, 6, 28 și 32; Legea nr. 82/1991 a contabilității și reglementările contabile aplicabile."
          }
        ]
      }
    ]
  };
