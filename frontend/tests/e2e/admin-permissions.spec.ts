import { test, expect } from "@playwright/test";

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

