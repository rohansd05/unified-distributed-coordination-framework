# VRMS — Project Handoff

Self-contained context for continuing the Vehicle Rental Management System project in a fresh chat.
Supersedes VRMS_Project_Context_Handoff.docx (which covered only Experiments 1–3).

---

## 1. Project basics

| Item | Value |
|---|---|
| Course | Software Engineering lab, Department of Computer Engineering, Sardar Patel Institute of Technology (SPIT), Andheri, Mumbai |
| Project | Vehicle Rental Management System (VRMS): a single agency renting cars and two-wheelers across 5 branches |
| Team | Rohan Dhumal (UID 2024300049) and Nidhi Rajkamal Dhyani (UID 2024300050). Both Division A, Batch A3 |
| Repo | https://github.com/rohansd05/vehicle-rental-management-system (branch `main`) |
| Dev machine | Windows, VS Code, Claude Code |
| End goal | (a) all 10 lab experiments submitted; (b) a fully deployed website with a professional UI |

### Experiment list
1. SRS (IEEE template)
2. Use case diagram
3. Class diagram
4. Interaction diagrams (sequence and collaboration)
5. Activity diagram
6. Data flow diagram, Levels 0/1/2
7. Work breakdown structure and scheduling
8. Risk Mitigation, Monitoring and Management (RMMM) plan
9. Implement one module
10. Test cases and unit testing

---

## 2. Ways of working (follow these)

- Ask clarifying questions before generating any file. Present content in chat first when practical.
- Give explicit terminal commands and ready-to-paste Claude Code prompts, not abstract advice.
- Explain the rationale whenever a technical decision affects a stated requirement.
- Rohan does manual formatting fixes in Word himself. When he asks for corrections, give step-by-step Word instructions; don't regenerate the file.
- Deliverables must be complete and submission-ready, matching the lecturer's reference templates exactly.
- Be honest when something can't be verified, and own mistakes plainly.

---

## 3. Experiment status

| # | Deliverable | Status | Notes |
|---|---|---|---|
| 1 | SRS | Done | 23 pp, body 11 pt. Maps Service removed. Figures 1–4 drawn. |
| 2 | Use case | Done | 11 pp, TNR 12. 9 actors incl. Guest and Branch Manager. include/extend labelled. |
| 3 | Class diagram | Done | 9 pp. 20 classes, all five relationship types, Java snippets. |
| 4 | Sequence and collaboration | Done | Book Vehicle, Return Vehicle, Close Maintenance Job. alt/opt fragments. |
| 5 | Activity diagram | Done | 7 pp, native Word shapes, 9 swimlanes, 5 phases. Explanation doc made (Nidhi). |
| 6 | DFD L0/L1/L2 | Done | 6 pp, Gane and Sarson. Explanation doc made (Rohan). See note below. |
| 7 | WBS and schedule | Done | 7 pp. 26 work packages (8/80 rule). Schedule 20 Jul – 12 Oct 2026, 63 working days. Each experiment date is a milestone. VRMS_Schedule.gan matches. |
| 8 | RMMM plan | Not started | Due Mon 5 Oct 2026. |
| 9 | Implement one module | Not started | Recommend the Booking module (strongest single module). |
| 10 | Test cases and unit testing | Not started | |

Experiment dates: Exp 1 10 Aug, Exp 2 18 Aug, Exp 3 25 Aug, Exp 4 8 Sep, Exp 5 21 Sep, Exp 6 28 Sep, Exp 7 and 8 5 Oct, Exp 9 6 Oct (implementation lab), Exp 10 12 Oct (evaluation week, project end).

Exp 6 note: on Level 1, Rohan manually added two flows, P8 → D3 "tariff definitions" and D7 → P8 "maintenance cost". They are floating shapes, so their endpoints were never verified from the file.

### Submission conventions

- **File names:** `AC_2024300049_ROHAN_SE_EXPn.pdf` for Rohan; `Nidhi_Dhyani_SE_ExpN_2024300050.docx` for Nidhi. Each student submits a copy carrying their own details.
- **Header:** SPIT crest header on every page (crest, "Sardar Patel Institute of Technology", campus line, "Department of Computer Engineering"). Exception: Exp 4 used the reference's "Bhartiya Vidya Bhavans" header.
- **Structure:** Title, Name/UID/Division/Batch block, Aim, Implementation/Theory, content, Conclusion.
- **Fonts:** Times New Roman 12; the SRS uses 11.
- **Spelling:** American from Exp 5 onward ("license", "behavior"). The class keeps its Exp 3 name `Licence`.
- **Page layout:** wide diagrams go on landscape pages.

---

## 4. Domain decisions (the code must honour these)

The authoritative SRS is docs/experiments/AC_2024300049_ROHAN_SE_EXP1 (1).pdf (Version 1.0, Maps Service removed). CLAUDE.md points to it.

### Scope

- The agency owns every vehicle. This is not peer-to-peer.
- Out of scope for Release 1.0: chauffeur bookings, loyalty programmes, insurance claims, telematics/GPS, multi-currency, aggregator integration.
- The Maps Service is removed entirely.
- Doorstep delivery stays. Each branch stores a list of serviceable pincodes/localities (`Branch.serviceable_areas`), and a delivery address is validated against that list. No geocoding.

### User classes / actors

- **Guest:** browses the catalogue and tariffs; cannot book. Not a persisted class.
- **Customer, Branch Staff, Maintenance Technician, Administrator:** the core user classes.
- **Branch Manager:** a restricted Administrator, limited to their own branch's vehicles, staff, bookings and reports.
- **External systems:** Payment Gateway, Notification Service (e-mail, SMS, push).

### Business rules

| ID | Rule |
|---|---|
| BR-1 | One confirmed booking per vehicle at any instant, plus a 60-minute turnaround buffer. |
| BR-2 | Licence valid for the whole rental. Minimum age 18 for a two-wheeler, 21 for a car. |
| BR-3 | Only categories the licence endorses. Deposit Rs 2,000 for a two-wheeler, Rs 5,000 for a car. |
| BR-4 | A blacklisted customer, or one with an outstanding due above 0, cannot book. |
| BR-5 | Rental period minimum 4 hours, maximum 30 days including extensions. |
| BR-6 | Charge = tariff + add-ons + usage + taxes − discount. Over 24 h is billed in whole days; under 24 h in whole hours, with a part-hour billed as full after a 15-minute grace. |
| BR-7 | Add-ons at the daily rate. One free helmet per rider on two-wheelers. |
| BR-8 | Cancellation refund: 100% at 48 h or more, 75% at 12–48 h, 50% under 12 h. A no-show retains 50%. The deposit is always refunded. |
| BR-9 | Cash only for branch pickup, recorded by a named staff member. |
| BR-10 | 15-minute return grace, then the hourly rate, capped at one day per 24 h. A delay over 6 h is billed as a full day. |
| BR-11 | Excess km charged at the category's excess-km rate. |
| BR-12 | Fuel/charge shortfall billed at the prevailing price plus 20%. |
| BR-13 | Only an Administrator changes tariffs. Changes never alter confirmed bookings or issued invoices; tariff history is retained. |
| BR-14 | Deposit balance refunded to the original instrument within 7 working days. |
| BR-15 | Preventive service due at 5,000 km or 6 months for a car, 3,000 km or 4 months for a two-wheeler, whichever comes first. |
| BR-16 | Only an Administrator or authorised technician closes a job or clears an Unsafe flag. The actor is recorded. |

Other fixed values:

- Booking reference format: `VRMS-YYYYMMDD-NNNN`.
- Pickup code: 6 digits.
- Payment hold: 15 minutes.
- No-show: declared 2 hours after the pickup time.
- Modification cut-off: 12 hours before pickup.

### State machines (only these transitions are legal)

**Booking:**
- Draft → Pending Payment
- Pending Payment → Confirmed, or → Draft (hold expires or payment fails)
- Confirmed → Confirmed (modify), → Active, → Cancelled, or → No-show
- Active → Active (extend), or → Completed

**Vehicle:**
- Available ↔ On Rent
- Available or On Rent → Under Maintenance → Available
- Any state → Unsafe; Unsafe → Available only via BR-16
- Retired only when no future booking exists

**Maintenance job:**
- Reported → Scheduled → In Progress → Completed
- Cancelled only from Reported or Scheduled

**Licence:** Not Submitted, Pending Verification, Verified, Rejected, Expired.

**Payment transaction:** Initiated, Processing, Succeeded, Failed, Reversed.

### Class model (Exp 3, final)

**Interfaces and inheritance:**
- «interface» User (name, address, mobile_no, email, password), implemented by:
  - Customer (customer_id, date_of_birth, is_blacklisted, outstanding_due; register, login, search_vehicle, view_history)
  - BranchStaff (staff_id, branch_id; handover_vehicle, return_vehicle)
  - MaintenanceTechnician (technician_id, workshop; record_job, close_job)
  - Administrator (admin_id, admin_pass; manage_fleet, define_tariff, approve_refund)
- BranchManager extends Administrator (branch_id; view_branch_reports)
- «interface» Vehicle (registration_no, brand, model, odometer, status; check_availability, update_status), implemented by:
  - Car (seating_capacity, transmission)
  - TwoWheeler (engine_cc)
- «interface» Payment (transaction_id, amount, status, timestamp; authorize, capture, refund), implemented by:
  - CardPayment (card_token, card_brand)
  - CashPayment (received_by)
  - OnlinePayment (upi_id)

**Concrete classes:**
- Licence (licence_number, category, expiry_date, status; verify, is_valid)
- Booking (booking_reference, pickup_datetime, return_datetime, status, pickup_code, total_amount; create, modify, cancel and extend booking)
- Branch (branch_id, branch_name, address, serviceable_areas; get_schedule)
- Tariff (hourly_rate, daily_rate, security_deposit, effective_from; calculate_price)
- Invoice (invoice_number, issue_date, tax_amount, total_amount; generate_invoice)
- ConditionReport (report_type, odometer_reading, fuel_level, signature; capture_report)
- MaintenanceJob (job_id, job_type, severity, status, total_cost; schedule_job, complete_job)

**Relationships:**

| Type | Relationships |
|---|---|
| Composition | Customer ◆ Licence (1:1); Booking ◆ Invoice (1:1); Booking ◆ ConditionReport (1:0..2) |
| Aggregation | Branch ◇ Vehicle (1:1..*); Branch ◇ BranchStaff (1:1..*) |
| Association | Customer–Booking (1:\*); Booking–Vehicle (\*:1); Booking–Branch (\*:1); Booking–Payment (1:1..\*); BranchStaff–ConditionReport (1:\*); Vehicle–MaintenanceJob (1:\*); MaintenanceTechnician–MaintenanceJob (1:\*); Administrator–Tariff (1:\*) |
| Dependency | Booking ⇢ Licence «verifies»; Vehicle ⇢ Tariff «priced by» |

Implementation additions required by the SRS: VehicleDocument, PartUsed, LedgerEntry, AuditLog, AddOn.

### Sequence diagrams (Exp 4) — the call order the code should follow

1. **Book Vehicle**
   - Customer → Booking.create_booking() → Licence.is_valid()
     - alt: licence invalid → booking refused
   - → Vehicle.check_availability()
     - alt: vehicle not free
   - → Tariff.calculate_price() → quotation → Payment.authorize()
     - alt: declined → hold retained
   - On success: Vehicle.update_status() → Invoice.generate_invoice() → status Confirmed and pickup code returned
2. **Return Vehicle**
   - BranchStaff → ConditionReport.capture_report()
     - alt: odometer lower than at handover → reject
   - → Tariff.calculate_price() (settlement under BR-10 to BR-12) → Payment.refund()
   - opt: damage found → MaintenanceJob.schedule_job() → Vehicle.update_status()
   - → Booking status Completed
3. **Close Maintenance Job**
   - Technician → MaintenanceJob.complete_job()
     - alt: work incomplete → cannot close
     - nested alt: vehicle flagged Unsafe → Administrator clears the flag (BR-16) → Vehicle.update_status()
   - → compute next service due (BR-15)

### Activity diagram (Exp 5)

**Swimlanes (9):** Guest, Customer, Branch Staff, VRMS System, Payment Gateway, Notification Service, Maintenance Technician, Administrator, Branch Manager.

**Phases:** drawn in 5 phases linked by connectors (A)–(D).

**Fork/join pairs:**
1. Atomic booking confirmation: reference + vehicle block + invoice + notification.
2. Handover: recording readings and customer signing.
3. Return settlement.

**Time events:**
- 15-minute hold expiry
- 2-hour no-show
- BR-15 preventive interval
- Daily start of business, which starts the admin/reporting flow

### DFD (Exp 6)

**Level 0:** process 0 with 8 external entities.

**Level 1 processes:**
1. Manage User Access and Registration
2. Search Vehicles and Check Availability
3. Manage Booking
4. Process Payment and Invoicing
5. Handle Vehicle Handover
6. Handle Vehicle Return and Settlement
7. Manage Vehicle Maintenance
8. Administer Fleet, Tariffs, Users and Reports

**Level 1 data stores:** D1 User and License, D2 Vehicle and Fleet, D3 Tariff, D4 Booking, D5 Payment Ledger, D6 Condition Report, D7 Maintenance Job, D8 Audit Log.

**Level 2 explosions:**
- Process 3 → 3.1–3.7
- Process 6 → 6.1–6.6
- Process 7 → 7.1–7.6

### WBS (Exp 7, as corrected)
Phases: 1.1 Planning, 1.2 Requirements, 1.3 Design, 1.4 Development, 1.5 Testing, 1.6 Deployment, 1.7 Maintenance. 26 work packages, all 8–80 hours.
Design packages mirror the experiments: 1.3.1 Use Case, 1.3.2 Class, 1.3.3 Interaction, 1.3.4 Activity, 1.3.5 Data Flow, 1.3.6 Database/UI/API.
Development (6–10 Oct) has 5 merged packages split between Rohan and Nidhi:
- 1.4.1 User, Licence, Fleet and Branch (Rohan)
- 1.4.2 Tariff, Search and Availability (Nidhi)
- 1.4.3 Booking and Payment (Rohan)
- 1.4.4 Handover, Return and Vehicle Maintenance (Nidhi)
- 1.4.5 Notification, Reports and Administration (Rohan)
Then, in order: 1.6.1 Server and Database Deployment (10 Oct), 1.6.2 Application Deployment and User Training (11 Oct), 1.7.1 Bug Fixing and Performance Tuning (11 Oct), 1.5.1 and 1.5.2 Testing (12 Oct). Sat 10 and Sun 11 Oct count as working days.

## 5. Tech stack and architecture

**Backend:**
- Django 5.2 LTS + Django REST Framework
- PostgreSQL 16
- Celery + Redis (+ Celery Beat for daily/preventive jobs)
- SimpleJWT
- drf-spectacular (OpenAPI 3.0)
- Payment gateway in sandbox/test mode behind an interface

**Frontend:**
- React 18 + TypeScript + Vite
- Tailwind CSS + shadcn/ui
- TanStack Query
- React Hook Form + Zod
- Recharts

**Testing:** pytest + pytest-django + factory-boy + freezegun; Vitest on the frontend. Coverage targets: 70% overall, 100% on pricing, availability and settlement.

**Non-negotiable rules.** These are already written into CLAUDE.md in the repo; the canonical copy is there:

1. Money is always `DecimalField` / `Decimal`, never float.
2. Timestamps are UTC (`USE_TZ=True`, `timezone.now()`).
3. Booking overlap is prevented by a PostgreSQL `ExclusionConstraint` on a `tsrange`, plus `select_for_update`. It must pass Reliability-1: 100 simultaneous confirmations produce exactly one success.
4. The ledger, audit log, issued invoices and signed condition reports are append-only. Corrections are made by credit note or adjustment.
5. Every endpoint has an explicit DRF permission class.
6. Business logic lives in `services.py`; views stay thin.
7. External services sit behind interfaces in `integrations/`, each with a `mock.py`.
8. Notifications are sent through Celery tasks, never inline.
9. Payment endpoints and webhooks are idempotent.
10. No card data is ever stored.

**Planned repo layout:**

```
backend/
  config/settings/          base.py, development.py, production.py, test.py
  apps/
    accounts
    fleet
    bookings
    rentals
    payments
    maintenance
    notifications
    reports
    core
  integrations/             payment_gateway, notification
frontend/src/
  api/
  components/
  features/                 auth, search, booking, payment, history, staff, technician, admin
docs/experiments/
scripts/
```

---

## 6. Current repo state (Claude Code read-only audit)

The repo has one commit (d9586e5, 22 Aug 2026). Only 4 tracked files exist: `CLAUDE.md`, `README.md`, `backend/requirements.txt` and `backend/requirements-dev.txt`.

There is no code at all:
- No Django project, `manage.py`, models or migrations
- No `frontend/`
- No tests
- No `.gitignore`, `.env.example`, Docker or CI/CD files

Completion: backend about 2% (dependency list only), frontend 0%. 
Update: Phase 0a is complete. docs/experiments/ holds the 7 experiment PDFs, .gitignore exists, the README clone URL and CLAUDE.md SRS path are fixed. Nothing else is built yet.

Problems the audit found:

1. The SRS and experiment deliverables are not in the repo, although CLAUDE.md points to `docs/experiments/`.
2. There is no `.gitignore`, so there is a risk of committing `.env`, `venv/` or `node_modules/`.
3. Possible conflict with CO-7 (only MIT/Apache/BSD licences allowed): `psycopg[binary]` is LGPL-3.0, and Pillow uses the HPND licence.
4. Redundant configuration libraries: `django-environ`, `python-decouple` and `dj-database-url`.
5. Both `argon2-cffi` and `bcrypt` are present. SE-2 mandates bcrypt with a work factor of at least 12.
6. `weasyprint` (needs GTK/Pango) and `python-magic` (needs libmagic) commonly fail on Windows.
7. The README refers to files that don't exist, and its clone URL still has a placeholder instead of `rohansd05`.
8. The README lists "wallet" as a payment method, but the class diagram has only Card, Cash and Online.
9. No `pyproject.toml` exists to configure ruff and black.
10. The `pytest -m concurrency` marker is not registered.

---

## 7. Open decisions — settle these before writing code

| # | Decision | Recommendation |
|---|---|---|
| D1 | Deployment target | **Undecided.** Needs a host that runs Django + Postgres + Redis + a Celery worker, plus static hosting for React. Candidates: a PaaS such as Render or Railway (backend, worker, managed Postgres and Redis) with Vercel or Netlify for the frontend, or a single VPS running docker-compose. Check current free-tier limits before choosing; Celery workers are often not free. |
| D2 | psycopg LGPL vs CO-7 | Amend CO-7 to permit LGPL libraries used unmodified as dependencies. Every mainstream Django PostgreSQL driver is LGPL. Record the decision in CLAUDE.md. |
| D3 | weasyprint / python-magic on Windows | Run the backend in Docker for local development (Linux container), or swap weasyprint for ReportLab (BSD) and drop python-magic in favour of extension and size validation. |
| D4 | Password hashing | Remove `argon2-cffi`. Set `BCryptSHA256PasswordHasher` first, with work factor ≥ 12. |
| D5 | Configuration library | Keep only `django-environ`. Remove `python-decouple` and `dj-database-url`. |
| D6 | Wallet payments | `OnlinePayment` covers UPI, net banking and wallets through a `channel` field. This keeps the class diagram intact and satisfies `Pay.Method`. |
| D7 | Agency name and currency | Use INR (Rs) and one configurable agency name in settings. Never hardcode either. |
| D8 | Mobile app | The SRS says Android/iOS (OE-2). Treat a responsive PWA as fulfilling it for Release 1.0, and state this in the final report. |
| D9 | Scope for the 6–12 Oct window | The schedule gives one week from the Exp 9 lab to the evaluation. Confirm what must be live by 12 Oct. I recommend a working vertical slice of the Booking module (licence check, availability, booking, payment sandbox, invoice) with a basic UI, deployed. The other modules can then follow after the lab evaluation. |

---

## 8. Document-generation lessons (for Exp 8 and 10 documents)

- Build with python-docx. `[Content_Types].xml` must be the first zip entry, with no directory entries.
- Inside `<w:txbxContent>`, justification is `w:val="center"`. Never use DrawingML's `ctr` — Word rejects the file, while LibreOffice silently tolerates it.
- Drawing IDs must be unique across the whole document. Never reset them per page.
- docx-js `ImageRun` needs an explicit `type` (png or jpg).
- No zero-size extents on lines (clamp to a minimum). `tblBorders` must sit in schema order inside `tblPr`. Remove python-docx's bare `<w:zoom/>`.
- Diagrams are native Word shapes (`wpg` groups wrapped in `mc:AlternateContent`) so Rohan can edit them, routed orthogonally around boxes.
- Validate every file with `/mnt/skills/public/docx/scripts/office/validate.py`, then render with LibreOffice to check page count and layout.
- LibreOffice opening a file does not prove Word will.

---

## 9. Build plan to a deployed product

| Phase | Work |
|---|---|
| 0 | Settle D1–D8. Add `.gitignore`, `.env.example`, `docker-compose.yml` (Postgres + Redis) and `pyproject.toml` (ruff/black/pytest markers). Commit the SRS and experiments into `docs/experiments/`. Scaffold Django (split settings, DRF default permissions, spectacular, Celery) and React (Vite + TS + Tailwind + shadcn). |
| 1 | Custom user model with roles. All models from the class diagram, plus VehicleDocument, PartUsed, LedgerEntry, AuditLog and AddOn. The booking `ExclusionConstraint`. JWT auth, OTP, lockout, audit mixin. Seed script. |
| 2 | Search and availability engine (BR-1 buffer); catalogue UI. |
| 3 | Booking and payments: eligibility, 15-minute hold, atomic `Book.Done`, state machine, modify/extend/cancel with BR-8, sandbox gateway, idempotency, invoice, ledger. **Concurrency test lands here.** This is also the Exp 9 module. |
| 4 | Handover and return: checklists, photos, signature, odometer check, settlement under BR-10 to BR-12. |
| 5 | Maintenance: lifecycle, BR-15 Celery Beat trigger, Unsafe flag under BR-16. |
| 6 | Admin, reports (branch-scoped for Branch Manager), notifications. |
| 7 | Hardening: WCAG 2.1 AA, keyboard navigation, 360–1920 px responsiveness, OWASP checks, IDOR tests. |
| 8 | Full test suite. Traceability matrix. Exp 10 document. Deployment to the D1 target with production settings, HTTPS and managed DB/Redis. Demo data. |

**Immediate next step:** settle D1–D8 with Rohan. Then write the Phase 0 Claude Code prompt plus its terminal commands. Each phase prompt should tell Claude Code to read CLAUDE.md first and keep the tree runnable.

**Parallel track:** the Exp 8 RMMM document can be written any time. It should follow the same template conventions as Exp 7.