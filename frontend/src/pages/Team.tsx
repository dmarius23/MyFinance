import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { usersApi, type StaffRole, type User } from "../api/users";
import { tasksApi, type UserTaskLoad } from "../api/tasks";
import { ApiError } from "../lib/apiClient";

const STAFF_ROLES: StaffRole[] = ["TENANT_ADMIN", "EMPLOYEE"];

/** MOD-02 — admin: manage firm users (roles, status) and see each user's task load. */
export function Team() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [error, setError] = useState<string | null>(null);

  const users = useQuery({ queryKey: ["users"], queryFn: usersApi.list });
  const load = useQuery({ queryKey: ["tasks-by-user"], queryFn: tasksApi.byUser });
  const loadBy = new Map<string, UserTaskLoad>((load.data ?? []).filter((l) => l.assigneeId).map((l) => [l.assigneeId!, l]));
  const unassigned = (load.data ?? []).find((l) => !l.assigneeId);

  const invalidate = () => { void qc.invalidateQueries({ queryKey: ["users"] }); void qc.invalidateQueries({ queryKey: ["tasks-by-user"] }); };
  const onErr = (e: unknown) => setError(e instanceof ApiError ? e.message : "Failed");
  const setRole = useMutation({ mutationFn: (v: { id: string; role: StaffRole }) => usersApi.setRole(v.id, v.role), onSuccess: invalidate, onError: onErr });
  const deactivate = useMutation({ mutationFn: (id: string) => usersApi.deactivate(id), onSuccess: invalidate, onError: onErr });
  const activate = useMutation({ mutationFn: (id: string) => usersApi.activate(id), onSuccess: invalidate, onError: onErr });

  const staff = (users.data ?? []).filter((u) => u.role === "TENANT_ADMIN" || u.role === "EMPLOYEE");

  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div>
        <div style={{ color: "var(--text-secondary)", fontSize: 12.5 }}>{t("team.crumb")}</div>
        <h2 style={{ margin: "2px 0 0", fontSize: 21, letterSpacing: "-0.01em" }}>{t("team.title")}</h2>
      </div>

      <InviteForm onDone={invalidate} onError={onErr} />
      {error && <div style={{ color: "#dc2626", fontSize: 13 }}>{error}</div>}

      <div className="card" style={{ padding: 0, overflow: "hidden" }}>
        <div style={{ minWidth: 820 }}>
          <div style={{ ...gridRow, background: "var(--th-bg)", ...thText }}>
            <div>{t("team.user")}</div>
            <div>{t("team.role")}</div>
            <div>{t("team.status")}</div>
            <div style={{ textAlign: "center" }}>{t("tasks.col.TODO")}</div>
            <div style={{ textAlign: "center" }}>{t("tasks.col.IN_PROGRESS")}</div>
            <div style={{ textAlign: "center" }}>{t("tasks.col.DONE")}</div>
            <div style={{ textAlign: "center" }}>{t("dashboard.overdue")}</div>
            <div style={{ textAlign: "right" }}>{t("statements.actions")}</div>
          </div>
          {users.isLoading && <div style={{ padding: 14, color: "var(--text-muted)" }}>{t("common.loading")}</div>}
          {staff.map((u) => {
            const l = loadBy.get(u.id);
            return (
              <div key={u.id} style={{ ...gridRow, borderTop: "1px solid var(--hair)" }}>
                <div>
                  <div style={{ fontWeight: 600 }}>{u.name ?? "—"}</div>
                  <div className="mono" style={{ color: "var(--text-muted)", fontSize: 11 }}>{u.email}</div>
                </div>
                <div>
                  <select value={u.role} disabled={setRole.isPending} onChange={(e) => setRole.mutate({ id: u.id, role: e.target.value as StaffRole })} style={select}>
                    {STAFF_ROLES.map((r) => <option key={r} value={r}>{t(`team.role.${r}`)}</option>)}
                  </select>
                </div>
                <div><StatusBadge status={u.status} t={t} /></div>
                <div style={{ textAlign: "center" }} className="mono">{l?.todo ?? 0}</div>
                <div style={{ textAlign: "center" }} className="mono">{l?.inProgress ?? 0}</div>
                <div style={{ textAlign: "center", color: "var(--text-muted)" }} className="mono">{l?.done ?? 0}</div>
                <div style={{ textAlign: "center" }}>{(l?.overdue ?? 0) > 0 ? <span className="pill round danger">{l!.overdue}</span> : <span className="mono" style={{ color: "var(--text-faint)" }}>0</span>}</div>
                <div style={{ textAlign: "right" }}>
                  {u.status === "INACTIVE"
                    ? <button onClick={() => activate.mutate(u.id)} disabled={activate.isPending}>{t("team.activate")}</button>
                    : <button onClick={() => deactivate.mutate(u.id)} disabled={deactivate.isPending} style={{ color: "#dc2626" }}>{t("team.deactivate")}</button>}
                </div>
              </div>
            );
          })}
          {(unassigned && (unassigned.todo + unassigned.inProgress + unassigned.overdue) > 0) && (
            <div style={{ ...gridRow, borderTop: "1px solid var(--hair)", background: "var(--th-bg)" }}>
              <div style={{ color: "var(--text-muted)", fontStyle: "italic" }}>{t("tasks.unassigned")}</div>
              <div /><div />
              <div style={{ textAlign: "center" }} className="mono">{unassigned.todo}</div>
              <div style={{ textAlign: "center" }} className="mono">{unassigned.inProgress}</div>
              <div style={{ textAlign: "center", color: "var(--text-muted)" }} className="mono">{unassigned.done}</div>
              <div style={{ textAlign: "center" }}>{unassigned.overdue > 0 ? <span className="pill round danger">{unassigned.overdue}</span> : <span className="mono" style={{ color: "var(--text-faint)" }}>0</span>}</div>
              <div />
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function InviteForm({ onDone, onError }: { onDone: () => void; onError: (e: unknown) => void }) {
  const { t } = useTranslation();
  const [f, setF] = useState({ email: "", name: "", role: "EMPLOYEE" as StaffRole });
  const invite = useMutation({
    mutationFn: () => usersApi.invite(f),
    onSuccess: () => { setF({ email: "", name: "", role: "EMPLOYEE" }); onDone(); },
    onError,
  });
  return (
    <div className="card" style={{ display: "flex", gap: 10, alignItems: "flex-end", flexWrap: "wrap" }}>
      <label style={{ display: "grid", gap: 4, flex: "1 1 200px" }}>
        <span style={lbl}>{t("team.email")}</span>
        <input type="email" value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} style={select} />
      </label>
      <label style={{ display: "grid", gap: 4, flex: "1 1 160px" }}>
        <span style={lbl}>{t("team.name")}</span>
        <input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} style={select} />
      </label>
      <label style={{ display: "grid", gap: 4 }}>
        <span style={lbl}>{t("team.role")}</span>
        <select value={f.role} onChange={(e) => setF({ ...f, role: e.target.value as StaffRole })} style={select}>
          {STAFF_ROLES.map((r) => <option key={r} value={r}>{t(`team.role.${r}`)}</option>)}
        </select>
      </label>
      <button className="primary" disabled={!f.email.trim() || invite.isPending} onClick={() => invite.mutate()}>{t("team.invite")}</button>
    </div>
  );
}

function StatusBadge({ status, t }: { status: User["status"]; t: (k: string) => string }) {
  const cls = status === "ACTIVE" ? "ok" : status === "INVITED" ? "warn" : "muted";
  return <span className={`pill round ${cls}`}>{t(`team.st.${status}`)}</span>;
}

const gridRow: React.CSSProperties = {
  display: "grid",
  gridTemplateColumns: "minmax(200px,1.6fr) 130px 96px 64px 84px 64px 80px 110px",
  alignItems: "center", gap: 8, padding: "10px 16px",
};
const thText: React.CSSProperties = { fontSize: 9.5, fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase", color: "#8a9794" };
const select: React.CSSProperties = { padding: "6px 8px", border: "1px solid var(--border)", borderRadius: 8, fontSize: 12.5, background: "var(--surface)", boxSizing: "border-box", width: "100%" };
const lbl: React.CSSProperties = { fontSize: 11, fontWeight: 600, color: "var(--text-muted)", textTransform: "uppercase", letterSpacing: "0.04em" };
