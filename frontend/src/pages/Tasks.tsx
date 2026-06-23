import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { tasksApi, staffApi, type Task, type TaskStatus } from "../api/tasks";
import { companiesApi } from "../api/companies";
import { Icon } from "../components/Icon";

const COLUMNS: TaskStatus[] = ["TODO", "IN_PROGRESS", "DONE"];
const ORDER: TaskStatus[] = ["TODO", "IN_PROGRESS", "DONE"];
const dmy = (iso: string) => new Date(iso).toLocaleDateString("ro-RO", { day: "numeric", month: "short" });

/** MOD-10 Internal Tasks — firm-staff board (TODO / IN_PROGRESS / DONE). */
export function Tasks() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [edit, setEdit] = useState<Partial<Task> | null>(null);

  const tasks = useQuery({ queryKey: ["tasks"], queryFn: tasksApi.list });
  const invalidate = () => void qc.invalidateQueries({ queryKey: ["tasks"] });
  const move = useMutation({
    mutationFn: ({ id, status }: { id: string; status: TaskStatus }) => tasksApi.changeStatus(id, status),
    onSuccess: invalidate,
  });

  const byStatus = (s: TaskStatus) => (tasks.data ?? []).filter((x) => x.status === s);

  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-end" }}>
        <div>
          <div style={{ color: "var(--text-secondary)", fontSize: 12.5 }}>{t("tasks.crumb")}</div>
          <h2 style={{ margin: "2px 0 0", fontSize: 21, letterSpacing: "-0.01em" }}>{t("tasks.title")}</h2>
        </div>
        <button className="primary" onClick={() => setEdit({ status: "TODO" })}>
          <Icon name="upload" size={13} style={{ verticalAlign: "-2px", marginRight: 4, transform: "rotate(0)" }} />{t("tasks.add")}
        </button>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "repeat(3, 1fr)", gap: 14, alignItems: "start" }}>
        {COLUMNS.map((s) => (
          <div key={s} style={{ background: "var(--th-bg)", borderRadius: 12, padding: 10, minHeight: 160 }}>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", padding: "2px 6px 10px" }}>
              <span style={{ fontSize: 12, fontWeight: 700, textTransform: "uppercase", letterSpacing: "0.05em", color: "var(--text-secondary)" }}>{t(`tasks.col.${s}`)}</span>
              <span className="mono" style={{ fontSize: 12, color: "var(--text-muted)" }}>{byStatus(s).length}</span>
            </div>
            <div style={{ display: "grid", gap: 8 }}>
              {byStatus(s).map((task) => {
                const idx = ORDER.indexOf(task.status);
                return (
                  <div key={task.id} className="card" style={{ padding: 11, cursor: "pointer" }} onClick={() => setEdit(task)}>
                    <div style={{ fontWeight: 600, fontSize: 13.5 }}>{task.title}</div>
                    <div style={{ display: "flex", gap: 6, flexWrap: "wrap", marginTop: 6 }}>
                      {task.companyName && <span className="pill round muted" style={{ maxWidth: 150, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}><Icon name="companies" size={9} style={{ verticalAlign: "-1px", marginRight: 3 }} />{task.companyName}</span>}
                      {task.dueDate && <span className={`pill round ${task.overdue ? "danger" : "muted"}`}>{dmy(task.dueDate)}</span>}
                    </div>
                    <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginTop: 8 }}>
                      <span style={{ fontSize: 11.5, color: task.assigneeName ? "var(--text-secondary)" : "var(--text-faint)" }}>
                        {task.assigneeName ?? t("tasks.unassigned")}
                      </span>
                      <div style={{ display: "flex", gap: 4 }} onClick={(e) => e.stopPropagation()}>
                        <button style={arrow} disabled={idx === 0 || move.isPending} title={t("tasks.moveBack")}
                          onClick={() => move.mutate({ id: task.id, status: ORDER[idx - 1] })}><Icon name="chevronLeft" size={13} /></button>
                        <button style={arrow} disabled={idx === ORDER.length - 1 || move.isPending} title={t("tasks.moveForward")}
                          onClick={() => move.mutate({ id: task.id, status: ORDER[idx + 1] })}><Icon name="chevronRight" size={13} /></button>
                      </div>
                    </div>
                  </div>
                );
              })}
              {byStatus(s).length === 0 && <div style={{ color: "var(--text-faint)", fontSize: 12, textAlign: "center", padding: "10px 0" }}>—</div>}
            </div>
          </div>
        ))}
      </div>

      {edit && <TaskModal task={edit} onClose={() => setEdit(null)} onSaved={() => { invalidate(); setEdit(null); }} />}
    </div>
  );
}

function TaskModal({ task, onClose, onSaved }: { task: Partial<Task>; onClose: () => void; onSaved: () => void }) {
  const { t } = useTranslation();
  const isNew = !task.id;
  const [form, setForm] = useState({
    title: task.title ?? "", details: task.details ?? "",
    assigneeId: task.assigneeId ?? "", companyId: task.companyId ?? "",
    dueDate: task.dueDate ?? "", status: (task.status ?? "TODO") as TaskStatus,
  });
  const staff = useQuery({ queryKey: ["staff"], queryFn: staffApi.list });
  const companies = useQuery({ queryKey: ["companies"], queryFn: companiesApi.list });

  const payload = () => ({
    title: form.title, details: form.details || null,
    assigneeId: form.assigneeId || null, companyId: form.companyId || null,
    dueDate: form.dueDate || null, status: form.status,
  });
  const save = useMutation({
    mutationFn: () => isNew ? tasksApi.create(payload()) : tasksApi.update(task.id!, payload()),
    onSuccess: onSaved,
  });
  const del = useMutation({ mutationFn: () => tasksApi.remove(task.id!), onSuccess: onSaved });

  return (
    <div style={overlay} onClick={onClose}>
      <div style={modal} onClick={(e) => e.stopPropagation()}>
        <div style={header}>
          <div style={{ color: "#f3f8f7", fontSize: 16, fontWeight: 700 }}>{isNew ? t("tasks.new") : t("tasks.edit")}</div>
          <button onClick={onClose} style={closeBtn}><Icon name="x" size={16} /></button>
        </div>
        <div style={{ padding: 16, display: "grid", gap: 10, overflowY: "auto" }}>
          <Field label={t("tasks.titleField")}>
            <input value={form.title} autoFocus onChange={(e) => setForm({ ...form, title: e.target.value })} style={input} />
          </Field>
          <Field label={t("tasks.details")}>
            <textarea value={form.details} onChange={(e) => setForm({ ...form, details: e.target.value })} style={{ ...input, minHeight: 70, fontFamily: "inherit", resize: "vertical" }} />
          </Field>
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10 }}>
            <Field label={t("tasks.assignee")}>
              <select value={form.assigneeId} onChange={(e) => setForm({ ...form, assigneeId: e.target.value })} style={input}>
                <option value="">{t("tasks.unassigned")}</option>
                {(staff.data ?? []).map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
              </select>
            </Field>
            <Field label={t("tasks.due")}>
              <input type="date" value={form.dueDate ?? ""} onChange={(e) => setForm({ ...form, dueDate: e.target.value })} style={input} />
            </Field>
          </div>
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10 }}>
            <Field label={t("tasks.company")}>
              <select value={form.companyId} onChange={(e) => setForm({ ...form, companyId: e.target.value })} style={input}>
                <option value="">{t("tasks.noCompany")}</option>
                {(companies.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.legalName}</option>)}
              </select>
            </Field>
            <Field label={t("tasks.status")}>
              <select value={form.status} onChange={(e) => setForm({ ...form, status: e.target.value as TaskStatus })} style={input}>
                {COLUMNS.map((s) => <option key={s} value={s}>{t(`tasks.col.${s}`)}</option>)}
              </select>
            </Field>
          </div>
        </div>
        <div style={footer}>
          {!isNew ? <button onClick={() => del.mutate()} disabled={del.isPending} style={{ color: "#dc2626" }}>{t("common.delete")}</button> : <span />}
          <div style={{ display: "flex", gap: 8 }}>
            <button onClick={onClose}>{t("common.cancel")}</button>
            <button className="primary" disabled={!form.title.trim() || save.isPending} onClick={() => save.mutate()}>{t("common.save")}</button>
          </div>
        </div>
      </div>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label style={{ display: "grid", gap: 4 }}>
      <span style={{ fontSize: 11, fontWeight: 600, color: "var(--text-muted)", textTransform: "uppercase", letterSpacing: "0.04em" }}>{label}</span>
      {children}
    </label>
  );
}

const arrow: React.CSSProperties = { width: 24, height: 22, display: "grid", placeItems: "center", padding: 0, border: "1px solid var(--border)", borderRadius: 6, background: "var(--surface)", color: "#52605d", cursor: "pointer" };
const overlay: React.CSSProperties = { position: "fixed", inset: 0, background: "rgba(0,0,0,0.4)", display: "flex", alignItems: "flex-start", justifyContent: "center", padding: "6vh 16px", zIndex: 60 };
const modal: React.CSSProperties = { background: "var(--surface)", borderRadius: 14, width: "min(560px, 96vw)", maxHeight: "86vh", display: "flex", flexDirection: "column", overflow: "hidden", boxShadow: "var(--shadow-modal)" };
const header: React.CSSProperties = { display: "flex", justifyContent: "space-between", alignItems: "center", background: "var(--chrome-bg)", padding: "12px 16px" };
const closeBtn: React.CSSProperties = { background: "none", border: "none", color: "var(--chrome-text)", cursor: "pointer" };
const input: React.CSSProperties = { width: "100%", boxSizing: "border-box", padding: "7px 9px", border: "1px solid var(--border)", borderRadius: 8, fontSize: 13, background: "var(--surface)" };
const footer: React.CSSProperties = { display: "flex", justifyContent: "space-between", alignItems: "center", padding: "12px 16px", borderTop: "1px solid var(--border)" };
