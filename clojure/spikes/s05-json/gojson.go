package main
import ("encoding/json";"fmt")
func main(){
  cases := []any{
    map[string]any{"b":1,"a":2,"c":3},
    map[string]any{"html":"a<b>c&d"},
    map[string]any{"f":1.0,"g":1.5,"h":100.0},
    map[string]any{"uni":"café ☃"},
    map[string]any{"esc":"tab\there\nnl"},
    []any{1,2.0,"x",nil,true},
  }
  for _,c := range cases { b,_ := json.Marshal(c); fmt.Println(string(b)) }
}
