import { test, expect } from "@playwright/test";

test("通讯录人数仅统计启用成员，停用成员可通过状态筛选查看", async ({ page }) => {
 const directory = {
  departments: [{ id: "root", parentId: null, name: "总公司", status: "0" }, { id: "child", parentId: "root", name: "研发部", status: "0" }],
  users: [
   { id: "1", departmentId: "root", account: "active-root", name: "启用直属成员", email: null, status: "0" },
   { id: "2", departmentId: "child", account: "active-child", name: "启用子部门成员", email: null, status: "0" },
   { id: "3", departmentId: null, account: "active-unassigned", name: "启用未分配成员", email: null, status: "0" },
   { id: "4", departmentId: "root", account: "disabled-root", name: "停用直属成员", email: null, status: "1" },
   { id: "5", departmentId: "child", account: "disabled-child", name: "停用子部门成员", email: null, status: "1" },
  ],
 };
 await page.route("**/api/auth/me", route => route.fulfill({ json: { userId: 1, name: "管理员", phone: "000", admin: true } }));
 await page.route("**/api/admin/directory", route => route.fulfill({ json: directory }));
 await page.goto("/");
 await page.getByRole("button", { name: "管理界面" }).click();
 const organization = page.locator(".admin-org");
 await expect(organization.getByRole("button", { name: "全部成员 3 位", exact: true })).toBeVisible();
 await expect(organization.getByRole("button", { name: "总公司 2 位", exact: true })).toBeVisible();
 await expect(organization.getByRole("button", { name: "研发部 1 位", exact: true })).toBeVisible();
 await expect(page.getByLabel("筛选状态")).toHaveValue("0");
 await expect(page.getByRole("row", { name: /启用未分配成员/ })).toBeVisible();
 await expect(page.getByRole("row", { name: /停用直属成员/ })).toHaveCount(0);
 await organization.getByRole("button", { name: "总公司 2 位", exact: true }).click();
 await expect(page.getByRole("row", { name: /启用子部门成员/ })).toBeVisible();
 await expect(page.getByRole("row", { name: /启用未分配成员/ })).toHaveCount(0);
 await page.getByLabel("筛选状态").selectOption("1");
 await expect(page.getByRole("row", { name: /停用直属成员/ })).toBeVisible();
 await expect(page.getByRole("row", { name: /停用子部门成员/ })).toBeVisible();
 await expect(organization.getByRole("button", { name: "总公司 2 位", exact: true })).toBeVisible();
 await organization.getByRole("button", { name: "全部成员 3 位", exact: true }).click();
 await page.getByLabel("筛选状态").selectOption("");
 await expect(page.locator("tbody tr")).toHaveCount(5);
 directory.users[1].status = "1";
 await page.getByRole("button", { name: "刷新", exact: true }).press("Enter");
 await expect(organization.getByRole("button", { name: "全部成员 2 位", exact: true })).toBeVisible();
 await expect(organization.getByRole("button", { name: "研发部 0 位", exact: true })).toBeVisible();
});

test("用户与权限保留通讯录接口并支持多部门授权和个人排除", async ({page}) => {
 await page.route("**/api/auth/me", route=>route.fulfill({json:{userId:1,name:"管理员",phone:"000",admin:true}}));
 await page.route("**/api/admin/directory", route=>route.fulfill({json:{departments:[{id:"a",parentId:null,name:"研发部",status:"0"},{id:"b",parentId:null,name:"市场部",status:"0"}],users:[{id:"1",departmentId:"a",account:"alice",name:"张三",email:null,status:"0"},{id:"2",departmentId:"b",account:"bob",name:"李四",email:null,status:"1"}]}}));
 await page.goto("/");await page.getByRole("button",{name:"管理界面"}).click();
 await expect(page.getByText("张三")).toBeVisible();
 await page.getByRole("tab",{name:"部门授权"}).click();await page.getByRole("button",{name:"新增部门授权"}).click();
 const dialog=page.getByRole("dialog",{name:"新增部门授权"});await dialog.getByLabel("选择 Skill").selectOption({label:"商情雷达"});await dialog.locator(".permission-select").click();await dialog.getByLabel("研发部").check();await dialog.getByLabel("市场部").check();await dialog.getByRole("button",{name:"预览授权"}).click();
 await expect(page.getByText("跳过：账号停用")).toBeVisible();await page.getByRole("button",{name:"确认保存"}).click();await expect(page.getByText("1 / 2 人")).toBeVisible();
 await page.getByRole("tab",{name:"用户列表"}).click();await page.getByRole("row",{name:/张三/}).getByRole("button",{name:"个人权限"}).click();const person=page.getByRole("dialog",{name:"个人权限"});await expect(person.getByText(/部门来源：研发部/)).toBeVisible();await person.locator(".permission-person-row").filter({hasText:"商情雷达"}).getByLabel("个人排除").check();await person.getByRole("button",{name:"保存修改"}).click();
 await page.getByRole("tab",{name:"部门授权"}).click();await expect(page.getByText("0 / 2 人")).toBeVisible();await page.getByRole("button",{name:"变更记录"}).click();await page.getByLabel("变更类型").selectOption({label:"个人权限"});await page.getByRole("button",{name:"详情"}).click();await expect(page.getByText("排除：商情雷达")).toBeVisible();await page.getByRole("button",{name:"返回变更记录"}).click();await expect(page.getByLabel("变更类型")).toHaveValue("个人权限");
});

